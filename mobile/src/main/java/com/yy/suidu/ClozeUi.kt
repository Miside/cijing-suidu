package com.yy.suidu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ImportKindPicker(kind: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val haptics = rememberIosHaptics()
    val position by animateFloatAsState(if (kind == ImportKind.CLOZE) 1f else 0f, spring(.9f, 600f), label = "import-kind")
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)).padding(4.dp)) {
        val slot = maxWidth / 2
        Box(Modifier.offset(x = slot * position).width(slot).height(44.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            listOf(ImportKind.ARTICLE to "英文文章", ImportKind.CLOZE to "完形填空").forEach { (value, label) ->
                Box(Modifier.weight(1f).height(44.dp).selectable(kind == value, enabled = enabled, role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { if (kind != value) haptics.selection(); onSelect(value) }), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 15.sp, fontWeight = if (kind == value) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun ClozeDraftEditor(draft: Draft, busy: Boolean, configured: Boolean, model: MobileModel, onGallery: () -> Unit) {
    val original = remember(draft.id, draft.text) { runCatching { ClozeCore.parse(draft.text) }.getOrNull() }
    var title by rememberSaveable(draft.id, draft.title) { mutableStateOf(draft.title) }
    var body by rememberSaveable(draft.id, draft.text) { mutableStateOf(original?.body.orEmpty()) }
    var options by rememberSaveable(draft.id, draft.text) { mutableStateOf(original?.optionText().orEmpty()) }
    var error by rememberSaveable(draft.id) { mutableStateOf("") }
    fun confirm(analyze: Boolean) {
        val result = runCatching { ClozeCore.fromParts(body, options).sourceText() }
        result.onSuccess { source ->
            error = ""
            if (analyze) model.analyze(draft.id, title, source) else model.saveText(draft.id, title, source)
        }.onFailure { error = it.message ?: "请核对空格和选项" }
    }
    IosBadge("保留原题 · 不填答案")
    IosTextField(title, { title = it.take(200) }, label = "完形填空标题", singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
    if (draft.notes.isNotBlank()) GlassCard { Text("识别疑点\n${draft.notes}", Modifier.padding(14.dp)) }
    if (draft.images.isNotEmpty()) IosTextButton(onClick = onGallery) { Text("对照原图 · ${draft.images.size} 页") }
    Text("请核对每个空格的原题号，正文和选项分开保存。空格用 [[21]] 标记，阅读时显示为 __21__。", fontSize = 13.sp,
        lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    IosTextField(body, { if (it.length <= LanguageCore.MAX_TEXT) { body = it; error = "" } }, label = "完形正文 · 保留空格",
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp), enabled = !busy,
        textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp))
    Text("选项格式：21. A. 原文  B. 原文  C. 原文  D. 原文\n每题另起一行；题号与正文空格一一对应。", fontSize = 12.sp,
        lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    IosTextField(options, { if (it.length <= LanguageCore.MAX_TEXT) { options = it; error = "" } }, label = "全部题目选项",
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp), enabled = !busy,
        textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 27.sp))
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp)
    Text("已保存 ${draft.chunks}/${draft.total} 批。修改原题后会重新解析；正文和选项文字都可精读，A/B/C/D 标签不计入词频。",
        fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    IosOutlineButton(onClick = { confirm(false) }, enabled = !busy && title.isNotBlank() && body.isNotBlank() && options.isNotBlank(),
        modifier = Modifier.fillMaxWidth()) { Text("保存原题与选项") }
    GlassAction(if (draft.stage == "analysis") "继续解析完形原题" else "确认原题并逐词解析", { confirm(true) },
        Modifier.fillMaxWidth(), !busy && configured && title.isNotBlank() && body.isNotBlank() && options.isNotBlank())
}
