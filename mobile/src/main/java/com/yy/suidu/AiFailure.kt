package com.yy.suidu

import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A bounded, actionable diagnosis; never store credentials, image bytes or full article responses. */
class AiFailure(val stage: String, val code: String, val reason: String, val action: String, val detail: String = "") :
    IllegalStateException("$stage · $code\n原因：$reason\n处理：$action" + if (detail.isBlank()) "" else "\n详情：$detail")

object AiDiagnostics {
    fun safe(value: String): String = value
        .replace(Regex("data:image/[^\\s\"']+", RegexOption.IGNORE_CASE), "[图片数据已隐藏]")
        .replace(Regex("(?i)(bearer\\s+|sk-)[a-z0-9_\\-]+"), "[密钥已隐藏]")
        .replace(Regex("(?i)(api[_ -]?key|authorization|token)\\s*[=:]\\s*[^\\s,;]+"), "$1=[已隐藏]")
        .take(700)

    fun http(status: Int, body: String, requestId: String = ""): AiFailure {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
        val detail = listOfNotNull("HTTP $status", error?.optString("code")?.takeIf { it.isNotBlank() },
            message?.let(::safe), requestId.takeIf { it.isNotBlank() }?.let { "请求号：${safe(it)}" }).joinToString(" · ")
        val (reason, action) = when (status) {
            401, 403 -> "服务端拒绝了密钥或账户权限。" to "在设置中重新核对密钥和账户权限；验证连接后再继续。"
            402 -> "DeepSeek 账户余额不足。" to "补充账户余额后再继续；更换图片无法解决余额问题。"
            413 -> "请求体或图片超过服务端限制。" to "减少每篇图片数量，或裁剪正文区域后重新提交。"
            429 -> "服务端触发限流。" to "稍后再手动继续；程序不会自动循环请求。"
            in 500..599 -> "DeepSeek 服务端异常。" to "保留草稿，稍后再继续；如持续出现，凭请求号联系服务方。"
            400, 422 -> "服务端拒绝了请求格式、参数或图片。" to "查看下方服务端原因：图片格式或尺寸问题请裁剪/重选；模型或参数不支持需要修正配置，原样重试通常无效。"
            404 -> "服务端没有找到请求的模型或接口。" to "在设置中验证模型连接，检查接口和模型配置。"
            else -> "服务端返回了非成功响应。" to "根据 HTTP 状态和服务端原因处理后再继续。"
        }
        return AiFailure("API 请求", "HTTP_$status", reason, action, detail)
    }

    fun completion(response: JSONObject, maxTokens: Int, stage: String): JSONObject {
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiFailure(stage, "MISSING_CHOICE", "响应缺少 choices[0]。", "检查服务端接口兼容性；本次停止，不重复发送相同请求。")
        val finish = choice.optString("finish_reason", "missing")
        if (finish != "stop") throw AiFailure(stage, "FINISH_${finish.uppercase()}",
            if (finish == "length") "输出达到长度上限，返回内容被截断。" else "模型未正常结束输出（$finish）。",
            if (stage == "图片识别") "将图片按文章分成更少页，或使用下方手动录入正文；本次没有自动重试。"
            else "缩短过长段落或减少解析范围后继续；已校验词位保留。",
            "finish_reason=$finish · 输出上限=$maxTokens · 实际输出=${response.optJSONObject("usage")?.optLong("completion_tokens") ?: 0}")
        val content = choice.optJSONObject("message")?.opt("content")
        if (content !is String || content.isBlank()) throw AiFailure(stage, "EMPTY_CONTENT", "模型返回了空内容或非文本内容。",
            "检查所选模型与接口是否兼容；本次停止，不自动反复请求。")
        return try { JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }
        catch (e: org.json.JSONException) { throw AiFailure(stage, "INVALID_JSON", "模型输出不是完整的 JSON 对象。",
            "图片识别可减少页数或手动录入正文；逐词解析可检查正文分段。下次解析会携带本次校验原因。", safe(e.message.orEmpty())) }
    }

    fun describe(error: Exception, stage: String): String = when (error) {
        is AiFailure -> error.message.orEmpty()
        is SocketTimeoutException -> AiFailure(stage, "NETWORK_TIMEOUT", "连接或读取响应超时，未收到完整结果。", "检查网络，图片识别可减少每次页数；请求可能已产生用量，本次没有自动重发。").message.orEmpty()
        is UnknownHostException -> AiFailure(stage, "DNS_FAILED", "无法解析 DeepSeek 服务器地址。", "检查网络、DNS 或代理后再继续。").message.orEmpty()
        is IOException -> AiFailure(stage, "NETWORK_IO", "传输中断或文件读写失败。", "检查网络与原图是否可读；不要通过反复点击绕过错误。", safe(error.message.orEmpty())).message.orEmpty()
        is org.json.JSONException -> AiFailure(stage, "SCHEMA_ERROR", "模型返回字段缺失、类型错误或结构不符合约定。", "查看字段详情后修正请求或正文；本次停止，已通过的批次保留。", safe(error.message.orEmpty())).message.orEmpty()
        else -> AiFailure(stage, "VALIDATION_FAILED", safe(error.message ?: error.javaClass.simpleName),
            "按原因修正图片、页序、正文或设置后再继续；本次不会自动反复请求。").message.orEmpty()
    }
}
