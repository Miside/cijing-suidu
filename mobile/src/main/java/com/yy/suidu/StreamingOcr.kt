package com.yy.suidu

import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Base64

interface RequestPayload { fun write(output: OutputStream, checkActive: () -> Unit) }

/** Encode images directly onto the request stream with a bounded source buffer. */
class StreamingOcrBody(private val images: List<File>, private val system: String, private val message: String) : RequestPayload {
    override fun write(output: OutputStream, checkActive: () -> Unit) {
        fun text(value: String) { output.write(value.toByteArray(Charsets.UTF_8)) }
        text("{\"model\":\"deepseek-flash\",\"thinking\":{\"type\":\"disabled\"},\"temperature\":0.1,\"max_tokens\":22000,\"response_format\":{\"type\":\"json_object\"},\"messages\":[{\"role\":\"system\",\"content\":")
        text(JSONObject.quote(system))
        text("},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":")
        text(JSONObject.quote(message)); text("}")
        var sourceBytes = 0L
        for (file in images) {
            checkActive()
            text(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,")
            val nonClosing = object : FilterOutputStream(output) {
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                override fun close() { flush() }
            }
            Base64.getEncoder().wrap(nonClosing).use { encoder ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        sourceBytes += n
                        require(sourceBytes <= 18 * 1024 * 1024) { "图片总量超过 18 MB" }
                        encoder.write(buffer, 0, n)
                    }
                }
            }
            text("\",\"detail\":\"original\"}}")
        }
        text("]}]}")
    }
}
