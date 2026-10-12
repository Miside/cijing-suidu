@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yy.suidu

import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class ScreenRoute(val tab: Int, val reader: ReaderData?) {
    val key: String get() = reader?.let { "article:${it.article.id}" } ?: "tab:$tab"
}

@Composable
fun MobileApp(model: MobileModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val prefs = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var darkSetting by rememberSaveable { mutableIntStateOf(prefs.getInt("dark", 0)) }
    var font by rememberSaveable { mutableFloatStateOf(prefs.getFloat("font", 21f)) }
    var glass by rememberSaveable { mutableStateOf(prefs.getBoolean("glass", true)) }
    val dark = when (darkSetting) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            window.navigationBarColor = if (dark) android.graphics.Color.rgb(30, 31, 49) else android.graphics.Color.rgb(238, 240, 249)
            window.isNavigationBarContrastEnforced = false
        }
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF8DBAFF), onPrimary = Color(0xFF062C60),
        primaryContainer = Color(0xFF294470), onPrimaryContainer = Color(0xFFDBE8FF), secondary = Color(0xFFB8B7F5),
        background = Color(0xFF111728), onBackground = Color(0xFFF0F2FA), surface = Color(0xFF192237), onSurface = Color(0xFFF0F2FA),
        surfaceVariant = Color(0xFF303B53), onSurfaceVariant = Color(0xFFBDCAE1), surfaceContainer = Color(0xFF20293D), surfaceContainerHighest = Color(0xFF303B53))
        else lightColorScheme(primary = Color(0xFF0061D5), onPrimary = Color.White,
            primaryContainer = Color(0xFFD8E7FF), onPrimaryContainer = Color(0xFF133C70), secondary = Color(0xFF645AAF),
            background = Color(0xFFF0F3FC), onBackground = Color(0xFF1B2538), surface = Color(0xFFF7F8FE), onSurface = Color(0xFF1B2538),
            surfaceVariant = Color(0xFFE4E9F5), onSurfaceVariant = Color(0xFF53627A), surfaceContainer = Color(0xFFEDF0F9), surfaceContainerHighest = Color(0xFFE4E9F5))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var pickTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var lookup by remember { mutableStateOf<Occurrence?>(null) }
    var historyLemma by rememberSaveable { mutableStateOf<String?>(null) }
    var gallery by remember { mutableStateOf<List<String>?>(null) }
    var deleteDraft by remember { mutableStateOf<Draft?>(null) }
    var deleteArticle by remember { mutableStateOf<Article?>(null) }
    LaunchedEffect(lookup?.articleId, lookup?.token?.id) { model.clearWordAnswer() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { if (pickTarget == null) selectedDraft = null; model.addImages(uris, pickTarget) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { model.export(it) } }
    val snackbar = remember { SnackbarHostState() }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.close() } }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) { snackbar.showSnackbar(state.message); model.clearMessage() }
    }
    LaunchedEffect(historyLemma) { historyLemma?.let { model.wordHistory(it) } ?: model.clearHistory() }
    BackHandler(enabled = state.reader != null || tab != 0) { if (state.reader != null) model.closeArticle() else tab = 0 }
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
        val backdrop = rememberGraphicsLayer()
        val contentLayer = rememberGraphicsLayer()
        val modalBlurRadius = with(LocalDensity.current) { 14.dp.toPx() }
        val modalBlur = remember(modalBlurRadius) { BlurEffect(modalBlurRadius, modalBlurRadius, TileMode.Clamp) }
        SideEffect {
            contentLayer.compositingStrategy = CompositingStrategy.Offscreen
            // The shared capture must stay unfiltered; glass controls also sample this layer.
            contentLayer.renderEffect = null
        }
        val scene = remember(backdrop, contentLayer, dark, glass) { GlassScene(backdrop, contentLayer, dark, glass) }
        val mood = animateFloatAsState(tab.toFloat(), tween(650, easing = FastOutSlowInEasing), label = "wallpaper-drift")
        val pages = rememberSaveableStateHolder()
        CompositionLocalProvider(LocalGlassScene provides scene, LocalContentColor provides colors.onSurface) {
        Box(Modifier.fillMaxSize()) {
        LiquidBackground(scene, mood)
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = Color.Transparent,
            topBar = { GlassHeader(state.reader != null, model::closeArticle) },
            bottomBar = { if (state.reader == null && !keyboardVisible) GlassNavigation(tab) { tab = it } },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .graphicsLayer {
                    // Observe dialog state in the rendering phase, including the transition back to null.
                    renderEffect = if (lookup != null || historyLemma != null || state.selectionTranslation != null) modalBlur else null
                }
                .onGloballyPositioned { scene.contentOrigin = it.positionInRoot() }
                .drawWithContent {
                    val edge = 120.dp.toPx().coerceAtMost(size.height)
                    val fadeEnd = (size.height - 4.dp.toPx()).coerceAtLeast(size.height - edge + 1f)
                    scene.content.record {
                        this@drawWithContent.drawContent()
                        if (state.reader == null) {
                            // Keep softened real detail under the floating glass; only the bottom edge dissolves fully.
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = size.height - edge, endY = fadeEnd), Offset(0f, size.height - edge), Size(size.width, edge), blendMode = BlendMode.DstOut)
                        }
                    }
                    drawLayer(scene.content)
                }) {
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.progress.ifBlank { "正在保存…" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::cancel) { Text("暂停") }
                    }
                }
                AnimatedContent(targetState = ScreenRoute(tab, state.reader), contentKey = { it.key }, modifier = Modifier.fillMaxWidth().weight(1f),
                    transitionSpec = {
                        val opening = initialState.reader == null && targetState.reader != null
                        val closing = initialState.reader != null && targetState.reader == null
                        val direction = if (targetState.tab >= initialState.tab) 1 else -1
                        val enter = fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = .82f, stiffness = 420f)) { width ->
                            if (opening) width * 2 / 3 else if (closing) -width / 6 else width * direction / 24
                        }
                        val leave = fadeOut(tween(160)) + slideOutHorizontally(spring(dampingRatio = .9f, stiffness = 480f)) { width ->
                            if (closing) width * 2 / 3 else if (opening) -width / 6 else -width * direction / 24
                        }
                        (enter togetherWith leave).using(SizeTransform(clip = true))
                    }, label = "page-transition") { route ->
                pages.SaveableStateProvider(route.key) {
                val reader = route.reader
                if (reader != null) ReaderScreen(reader, font, lookup, { o -> lookup = o; model.lookup(o) },
                    { selection -> lookup = null; model.translateSelection(selection) }, { gallery = reader.images })
                else when (route.tab) {
                    0 -> LibraryScreen(state, model::openArticle, { selectedDraft = it; tab = 1 }, { tab = 1 }, { deleteArticle = it })
                    1 -> {
                        val draft = state.drafts.firstOrNull { it.id == selectedDraft } ?: state.drafts.firstOrNull()
                        ImportScreen(state, draft, model,
                            newImages = { pickTarget = null; picker.launch(arrayOf("image/*")) },
                            moreImages = { id -> pickTarget = id; picker.launch(arrayOf("image/*")) },
                            onSettings = { tab = 4 }, onGallery = { gallery = it }, onDelete = { deleteDraft = it })
                    }
                    2 -> VocabularyScreen(state.vocabulary, { historyLemma = it })
                    3 -> ReviewScreen(state, model, speaker)
                    4 -> SettingsScreen(state, model, darkSetting, { value -> darkSetting = value; prefs.edit().putInt("dark", value).apply() },
                        font, { value -> font = value; prefs.edit().putFloat("font", value).apply() },
                        glass, { value -> glass = value; prefs.edit().putBoolean("glass", value).apply() },
                        { exporter.launch("随读学习数据-${java.text.SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.json") })
                }
                }
                }
            }
        }
        }
        lookup?.let { o ->
            val wordSheetHeight = LocalConfiguration.current.screenHeightDp.dp * .72f
            ModalBottomSheet(onDismissRequest = { lookup = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .95f), tonalElevation = 0.dp, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = wordSheetHeight).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.token.surface, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { speaker.say(o.token.surface) }) { Text("朗读") }
                    }
                    Text(o.lemma, color = MaterialTheme.colorScheme.primary)
                    Text("本文释义", style = MaterialTheme.typography.labelLarge)
                    Text(ReadingTools.meaningWithPos(o.meaning, o.pos), style = MaterialTheme.typography.titleLarge)
                    state.reader?.phrases?.filter { o.token.id in it.tokenIds }?.forEach { p ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("${p.text}\n${p.meaning}", Modifier.padding(12.dp))
                        }
                    }
                    Text("其他常见释义", style = MaterialTheme.typography.labelLarge)
                    o.common.forEach { Text("• $it") }
                    Text("AI 生成释义 · 来源：${o.articleTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RatingButtons(state.busy) { rating -> model.rate(o, rating); lookup = null }
                    TextButton(onClick = { lookup = null; historyLemma = o.lemma }) { Text("查看这个词的所有出现位置") }
                    TextButton(onClick = { model.removeReview(o.lemma) }, enabled = !state.busy) { Text("将 ${o.lemma} 移出复习队列") }
                    WordQuestionPanel(o, state, model)
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        state.selectionTranslation?.let { translated ->
            ModalBottomSheet(onDismissRequest = model::dismissSelection, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .95f), tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("选段翻译", style = MaterialTheme.typography.headlineSmall)
                    SelectionContainer { Text(translated.selection.text, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 30.sp) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    if (translated.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在查找已保存译文或翻译选段…") }
                    else if (translated.error.isNotBlank()) {
                        Text(translated.error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { model.translateSelection(translated.selection) }, enabled = !state.busy) { Text("重试翻译") }
                    } else {
                        SelectionContainer { Text(translated.translation, style = MaterialTheme.typography.titleLarge, lineHeight = 30.sp) }
                        Text(translated.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = model::dismissSelection) { Text("返回文章") }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        historyLemma?.let { lemma ->
            ModalBottomSheet(onDismissRequest = { historyLemma = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .95f), tonalElevation = 0.dp, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
                val history = state.wordHistory.filter { it.lemma == lemma }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(lemma, style = MaterialTheme.typography.headlineMedium); Text("共 ${history.size} 次 · 同一释义的文字分组仅作参考", style = MaterialTheme.typography.bodySmall) }
                    item { TextButton(onClick = { model.removeReview(lemma) }, enabled = !state.busy) { Text("将 $lemma 移出复习队列") } }
                    items(history, key = { "${it.articleId}:${it.token.id}" }) { o ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${o.token.surface} · ${ReadingTools.meaningWithPos(o.meaning, o.pos)}", fontWeight = FontWeight.SemiBold)
                                Text(o.paragraphText, maxLines = 5, fontFamily = FontFamily.Serif)
                                TextButton(onClick = { historyLemma = null; model.openArticle(o.articleId) }) { Text("打开原文 · ${o.articleTitle}") }
                                RatingButtons(state.busy) { rating -> model.rate(o, rating) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
        gallery?.let { paths ->
            Dialog(onDismissRequest = { gallery = null }) {
                Surface(shape = MaterialTheme.shapes.large) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(12.dp)) {
                        item { TextButton(onClick = { gallery = null }) { Text("关闭原图") } }
                        items(paths.indices.toList()) { i -> Text("第 ${i + 1} 页", Modifier.padding(8.dp)); LocalImage(paths[i], Modifier.fillMaxWidth().height(480.dp)) }
                    }
                }
            }
        }
        deleteDraft?.let { d ->
            AlertDialog(onDismissRequest = { deleteDraft = null }, title = { Text("删除这份草稿？") },
                text = { Text("草稿正文、原图和已完成的解析批次将从本机删除。") },
                confirmButton = { TextButton(onClick = { model.discard(d.id); deleteDraft = null }, enabled = !state.busy) { Text("删除草稿") } },
                dismissButton = { TextButton(onClick = { deleteDraft = null }) { Text("保留") } })
        }
        deleteArticle?.let { a ->
            AlertDialog(onDismissRequest = { deleteArticle = null }, title = { Text("删除这篇文章？") },
                text = { Text("${a.title}\n正文和原图将从本机删除，词频会重新统计。只来自此文的复习卡会移除；其他文章仍有的同义项会保留复习进度。") },
                confirmButton = { TextButton(onClick = { model.deleteArticle(a.id); deleteArticle = null }, enabled = !state.busy) { Text("确认删除文章") } },
                dismissButton = { TextButton(onClick = { deleteArticle = null }) { Text("保留文章") } })
        }
        }
    }
}

@Composable private fun LibraryScreen(state: MobileState, open: (String) -> Unit, draft: (String) -> Unit, add: () -> Unit, delete: (Article) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Spacer(Modifier.height(10.dp))
            Text("我的阅读空间", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
            Text("从一篇文章开始", fontSize = 29.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
            Text("让遇见的词，成为你的积累。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(state.articles.size to "文章", state.vocabulary.size to "词元", state.articles.sumOf { it.words } to "词次").forEach { (value, label) ->
                    GlassCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(value.toString(), fontSize = 27.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { GlassAction("导入文章图片", add, Modifier.fillMaxWidth()) }
        if (!state.loaded) item { Text("正在读取本地词库…") }
        if (state.loaded && state.articles.isEmpty() && state.drafts.isEmpty()) item {
            GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassSurface(Modifier.size(56.dp), radius = 19.dp) { NavGlyph(0, MaterialTheme.colorScheme.primary, Modifier.size(29.dp).align(Alignment.Center)) }
                Text("你的书库还没有文章", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("先在设置里填写 DeepSeek API Key，再选取文章截图或照片。解析完成后，每个词都可以点开查看。", fontSize = 14.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("选择图片", "点词阅读", "间隔复习").forEachIndexed { index, label ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("0${index + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } }
        }
        if (state.drafts.isNotEmpty()) item { Text("待处理草稿", style = MaterialTheme.typography.titleMedium) }
        items(state.drafts, key = { it.id }) { d ->
            GlassCard(Modifier.fillMaxWidth().clickable { draft(d.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(d.title, fontWeight = FontWeight.SemiBold)
                    Text("${d.images.size} 页 · ${stageLabel(d.stage)} · ${d.chunks}/${d.total} 批", style = MaterialTheme.typography.bodySmall)
                    if (d.error.isNotBlank()) Text(d.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.articles.isNotEmpty()) item { Text("已入库 · 可离线阅读", style = MaterialTheme.typography.titleMedium) }
        items(state.articles, key = { it.id }) { a ->
            GlassCard(Modifier.fillMaxWidth().clickable { open(a.id) }) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text(a.text.take(160).replace('\n', ' '), maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${a.words} 词次 · ${articleTime(a.created)}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { delete(a) }, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) { Text("删除文章") }
                }
            }
        }
        item { Spacer(Modifier.height(196.dp)) }
    }
}

@Composable private fun ImportScreen(
    state: MobileState, draft: Draft?, model: MobileModel, newImages: () -> Unit,
    moreImages: (String) -> Unit, onSettings: () -> Unit, onGallery: (List<String>) -> Unit, onDelete: (Draft) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("图片 → 文章 → 词库", style = MaterialTheme.typography.headlineSmall)
        Text("每篇最多 12 张图片。先排页序，再识别正文。", style = MaterialTheme.typography.bodyMedium)
        GlassAction("新建文章 · 选择图片", newImages, Modifier.fillMaxWidth(), !state.busy)
        if (!state.configured) GlassCard { Column(Modifier.padding(16.dp)) {
            Text("先填写你的 DeepSeek API Key")
            TextButton(onClick = onSettings) { Text("前往设置") }
        } }
        if (draft != null) {
            key(draft.id) {
                Text("当前草稿 · ${stageLabel(draft.stage)}", style = MaterialTheme.typography.titleMedium)
                if (draft.error.isNotBlank()) Text(draft.error, color = MaterialTheme.colorScheme.error)
                if (draft.stage == "images") {
                    draft.images.forEachIndexed { i, path ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LocalImage(path, Modifier.width(90.dp).height(120.dp).clickable { onGallery(draft.images) })
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text("第 ${i + 1} 页", fontWeight = FontWeight.SemiBold)
                                    Row {
                                        TextButton(onClick = { model.orderImages(draft.id, i, i - 1) }, enabled = i > 0 && !state.busy) { Text("上移") }
                                        TextButton(onClick = { model.orderImages(draft.id, i, i + 1) }, enabled = i < draft.images.lastIndex && !state.busy) { Text("下移") }
                                    }
                                    Row {
                                        TextButton(onClick = { model.rotateImage(draft.id, i) }, enabled = !state.busy) { Text("旋转") }
                                        TextButton(onClick = { model.removeImage(draft.id, i) }, enabled = !state.busy) { Text("移除") }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { moreImages(draft.id) }, enabled = !state.busy && draft.images.size < 12) { Text("添加图片到这篇文章") }
                    Text("点“联网识别”会将这些图片发送至 DeepSeek，并产生 API 用量。原图也保存在本机。请保持应用运行；中断后可再次打开草稿。", style = MaterialTheme.typography.bodySmall)
                    GlassAction("联网识别完整文章", { model.recognize(draft.id) }, Modifier.fillMaxWidth(), !state.busy && state.configured && draft.images.isNotEmpty())
                } else {
                    var title by rememberSaveable(draft.title) { mutableStateOf(draft.title) }
                    var text by rememberSaveable(draft.text) { mutableStateOf(draft.text) }
                    OutlinedTextField(title, { title = it.take(200) }, label = { Text("文章标题") }, shape = RoundedCornerShape(18.dp), colors = glassFieldColors(), enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    if (draft.notes.isNotBlank()) GlassCard { Text("识别疑点\n${draft.notes}", Modifier.padding(14.dp)) }
                    TextButton(onClick = { onGallery(draft.images) }) { Text("对照原图 · ${draft.images.size} 页") }
                    Text("这里可以修改漏字、错字和跨页衔接。每页之间暂留空行；需要时可合并。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { if (it.length <= LanguageCore.MAX_TEXT) text = it }, label = { Text("完整正文") },
                        shape = RoundedCornerShape(18.dp), colors = glassFieldColors(), enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp), textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp))
                    Text("已保存 ${draft.chunks}/${draft.total} 批。修改正文后会清空已完成批次并重新解析。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { model.saveText(draft.id, title, text) }, enabled = !state.busy && title.isNotBlank() && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存正文") }
                    GlassAction(if (draft.stage == "analysis") "继续补齐解析" else "确认并逐词解析", { model.analyze(draft.id, title, text) }, Modifier.fillMaxWidth(), !state.busy && state.configured && title.isNotBlank() && text.isNotBlank())
                    Text("每个词位都会解析，包括常见功能词。已校验的词位逐次保存在本机，遗漏只补缺项；全部校验完成后加入书库和词频统计。", style = MaterialTheme.typography.bodySmall)
                }
                Text("已记录 API 用量：输入 ${draft.inputTokens} / 输出 ${draft.outputTokens} tokens。未收到响应的请求用量不在此计数，费用以 DeepSeek 账户为准。", style = MaterialTheme.typography.bodySmall)
                Text("服务端缓存命中 ${draft.cacheHitTokens} / 未命中 ${draft.cacheMissTokens} 输入 tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onDelete(draft) }, enabled = !state.busy) { Text("删除这份草稿") }
            }
        }
        Spacer(Modifier.height(196.dp))
    }
}

@Composable private fun ReaderScreen(data: ReaderData, font: Float, selected: Occurrence?, onWord: (Occurrence) -> Unit, onSelection: (TextSelection) -> Unit, images: () -> Unit) {
    var translated by rememberSaveable(data.article.id) { mutableStateOf(false) }
    val paragraphs = remember(data.article.id) { LanguageCore.paragraphs(data.article.text) }
    val occurrences = remember(data.article.id) { data.occurrences.associateBy { it.token.id } }
    val selectedIds = if (selected == null || selected.articleId != data.article.id) emptySet()
        else data.phrases.filter { selected.token.id in it.tokenIds }.flatMap { it.tokenIds }.toSet() + selected.token.id
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(data.article.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
            Text("入库于 ${articleTime(data.article.created)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${data.article.words} 词次 · 点词查看本文释义", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            Text("长按正文，拖动选择后点“翻译”", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { translated = !translated }) { Text(if (translated) "隐藏段落译文" else "显示段落译文") }
                if (data.images.isNotEmpty()) TextButton(onClick = images) { Text("查看原图") }
            }
        }
        items(paragraphs, key = { it.id }) { p ->
            GlassCard(Modifier.fillMaxWidth().border(BorderStroke(.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = .14f)), RoundedCornerShape(26.dp)), strong = true) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("第 ${p.id + 1} 段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    SelectableParagraph(p, font, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary,
                        selectedIds, Modifier.fillMaxWidth(), onWord = { offset ->
                            p.tokens.firstOrNull { offset in (it.start - p.start) until (it.end - p.start) }?.let { occurrences[it.id]?.let(onWord) }
                        }, onTranslate = { start, end -> onSelection(TextSelection(data.article.id, p.id, start, end, p.text)) })
                    if (translated) data.translations[p.id]?.let { translation ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                        SelectionContainer { Text(translation, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 27.sp) }
                    }
                }
            }
        }
        item { Text("释义与译文由 DeepSeek 生成。词频来自本机保存的正文词位。", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(65.dp)) }
    }
}

@Composable private fun VocabularyScreen(words: List<Vocabulary>, onWord: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var difficult by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(words, search, difficult) { words.filter { it.lemma.contains(search.trim(), true) && (!difficult || it.difficult > 0) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("你的文章词频", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
        Text("按原形合并 · ${words.size} 个词元 · ${words.sumOf { it.count }} 词次", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, shape = RoundedCornerShape(18.dp), colors = glassFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, label = { Text("搜索英文词元") })
        FilterChip(selected = difficult, onClick = { difficult = !difficult }, label = { Text("只看不熟悉的词") })
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item { Text(if (words.isEmpty()) "导入并解析文章后，词库会自动建立。" else "没有匹配的词。", Modifier.padding(20.dp)) }
            items(filtered, key = { it.lemma }) { w ->
                GlassCard(Modifier.fillMaxWidth().clickable { onWord(w.lemma) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.lemma, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                            Text("${w.articles} 篇文章 · 查词 ${w.clicks} 次${if (w.difficult > 0) " · 待巩固" else ""}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${w.count}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Spacer(Modifier.height(196.dp)) }
        }
    }
}

@Composable private fun ReviewScreen(state: MobileState, model: MobileModel, speaker: Speaker) {
    val card = state.due.firstOrNull()
    var revealedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val revealed = card != null && revealedFor == card.id
    LaunchedEffect(card?.id, card?.context, revealed, state.busy) {
        if (card != null && revealed && !state.busy &&
            state.reviewTranslation?.key != LanguageCore.hash("${card.id}\u0000${card.context}")) model.translateReview(card)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("在原句里复习", style = MaterialTheme.typography.headlineSmall)
        Text("已加入 ${state.reviewCount} 条语境卡 · 当前到期 ${state.due.size}${if (state.due.size == 100) "+" else ""} 条", style = MaterialTheme.typography.bodySmall)
        if (card == null) {
            GlassCard { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前没有到期的复习", style = MaterialTheme.typography.titleLarge)
                Text("阅读时点开单词，标记“没记住 / 模糊 / 记住了”，就会建立带原文语境的复习卡。")
                TextButton(onClick = model::refreshNow) { Text("刷新到期卡片") }
            } }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(card.lemma, modifier = Modifier.weight(1f), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Serif)
                TextButton(onClick = { speaker.say(card.lemma) }) { Text("朗读单词") }
            }
            GlassCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val sentence = remember(card) { buildAnnotatedString {
                    append(card.context)
                    if (card.targetStart in 0 until card.targetEnd && card.targetEnd <= card.context.length)
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), card.targetStart, card.targetEnd)
                } }
                Text(sentence, fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 32.sp)
                Text(if (card.shortened) "原句节选 · 已省略前后部分" else "原文短句", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            Text("回想这个词在这句原文里的意思。")
            if (card.contextChoices > 1) Text("有 ${card.contextChoices} 个同义项原句，每完成 3 次复习适当换一句。", style = MaterialTheme.typography.bodySmall)
            AnimatedContent(revealed, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) }, label = "review-answer") { answer ->
            if (!answer) GlassAction("显示本文释义", { revealedFor = card.id; model.translateReview(card) }, Modifier.fillMaxWidth(), !state.busy, arrow = false)
            else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(ReadingTools.meaningWithPos(card.meaning, card.pos), style = MaterialTheme.typography.titleLarge)
                GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("这句的意思", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    val translation = state.reviewTranslation?.takeIf { it.key == LanguageCore.hash("${card.id}\u0000${card.context}") }
                    if (translation == null || translation.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备句子译文…") }
                    else if (translation.error.isNotBlank()) {
                        Text(translation.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { model.translateReview(card) }, enabled = !state.busy) { Text("重试句子翻译") }
                    } else SelectionContainer { Text(translation.text, lineHeight = 27.sp) }
                } }
                card.common.forEach { Text("• $it") }
                RatingButtons(state.busy) { rating -> model.rateCard(card, rating) }
                Text("没记住：10 分钟后；模糊：1 天后；记住了：逐步延长至 1、3、7、14、30、60、120 天。", style = MaterialTheme.typography.bodySmall)
            }
            }
            TextButton(onClick = { model.openArticle(card.articleId) }) { Text("回到来源文章") }
            TextButton(onClick = { model.removeReview(card.lemma) }, enabled = !state.busy) { Text("将 ${card.lemma} 移出复习队列") }
            if (card.sourceTitle.isNotBlank()) Text(card.sourceTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(196.dp))
    }
}

private fun articleTime(time: Long) = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(time))

@Composable private fun WordQuestionPanel(word: Occurrence, state: MobileState, model: MobileModel) {
    var question by rememberSaveable(word.articleId, word.token.id) { mutableStateOf("") }
    GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("问 DeepSeek", style = MaterialTheme.typography.titleMedium)
        Text("结合这个词和原文解答", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("为什么是这个词义？", "解释这句的语法").forEach { prompt ->
                SuggestionChip(onClick = { question = prompt; model.askWord(word, prompt) }, enabled = !state.busy, label = { Text(prompt, fontSize = 11.sp) })
            }
        }
        OutlinedTextField(question, { question = it.take(600) }, label = { Text("关于这个词或文章的问题") },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = glassFieldColors(), minLines = 2, maxLines = 4)
        GlassAction("发送问题", { model.askWord(word, question) }, Modifier.fillMaxWidth(), !state.busy && question.isNotBlank(), arrow = false)
        state.wordAnswer?.takeIf { it.key == "${word.articleId}:${word.token.id}" }?.let { answer ->
            if (answer.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在结合原文思考…") }
            else if (answer.error.isNotBlank()) Text(answer.error, color = MaterialTheme.colorScheme.error)
            else {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                SelectionContainer { Text(answer.text, lineHeight = 27.sp) }
                Text(if (answer.cached) "本机已保存的回答" else "DeepSeek 生成 · 已保存到本机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }
}

@Composable private fun SettingsScreen(
    state: MobileState, model: MobileModel, dark: Int, onDark: (Int) -> Unit,
    font: Float, onFont: (Float) -> Unit, glass: Boolean, onGlass: (Boolean) -> Unit, export: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("连接与偏好", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("DeepSeek 连接", style = MaterialTheme.typography.titleLarge)
        Text("图片识别与释义使用 deepseek-flash。文章、词库、学习记录保存在本机，入库后可离线使用。")
        Text(if (state.configured) "已保存 API Key（本机加密）" else "尚未填写 API Key", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(key, { key = it }, shape = RoundedCornerShape(18.dp), colors = glassFieldColors(), singleLine = true, label = { Text(if (state.configured) "填写新的 API Key" else "粘贴 API Key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassAction("保存密钥", { model.saveKey(key); key = "" }, Modifier.weight(1f), !state.busy && key.isNotBlank(), arrow = false)
            OutlinedButton(onClick = model::checkConnection, enabled = !state.busy && state.configured, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("验证连接") }
        }
        if (state.configured) TextButton(onClick = model::removeKey, enabled = !state.busy) { Text("移除本机密钥") }
        } }
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Token 与缓存", style = MaterialTheme.typography.titleLarge)
        Text("输入 ${state.usage[0]} · 输出 ${state.usage[1]} tokens\n服务端缓存命中 ${state.usage[2]} · 未命中 ${state.usage[3]} tokens")
        Text("已有释义本地复用，只为新词补充。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起节省说明" else "查看节省说明") }
        AnimatedVisibility(details, enter = fadeIn(tween(180)) + androidx.compose.animation.expandVertically(tween(250)), exit = fadeOut(tween(130)) + androidx.compose.animation.shrinkVertically(tween(220))) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("常见释义只为新词生成；相同图片、相同段落优先复用本机缓存；逐词输出采用紧凑格式，不携带长聊天历史。固定提示词与段落放在前部，变化的任务范围放在末尾。", style = MaterialTheme.typography.bodySmall)
            Text("DeepSeek 服务端缓存自动启用，命中由服务端决定，不能保证每次命中，也不会为预热缓存额外调用 API。数字来自实际响应，并非估算费用。", style = MaterialTheme.typography.bodySmall)
        }
        }
        } }
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("阅读外观", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("液态玻璃", fontWeight = FontWeight.SemiBold); Text("透明透镜、边缘折射与触摸高光", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = glass, onCheckedChange = onGlass)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("随系统", "浅色", "深色").forEachIndexed { i, title -> FilterChip(selected = dark == i, onClick = { onDark(i) }, label = { Text(title) }) }
        }
        Text("正文字号 ${font.toInt()} sp")
        Slider(value = font, onValueChange = onFont, valueRange = 17f..30f, steps = 12)
        } }
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本地数据", style = MaterialTheme.typography.titleLarge)
        Text("${state.articles.size} 篇文章 · ${state.vocabulary.size} 个词元 · ${state.reviewCount} 条复习卡")
        OutlinedButton(onClick = export, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出文章、词库和复习记录") }
        Text("导出为 JSON，不包含 API Key 和原图。当前版本尚不支持导入恢复。卸载应用会清除本机数据。", style = MaterialTheme.typography.bodySmall)
        } }
        Text("随读 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(196.dp))
    }
}

@Composable private fun RatingButtons(disabled: Boolean, onRate: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("没记住", "模糊", "记住了").forEachIndexed { index, text ->
            OutlinedButton(onClick = { onRate(index) }, enabled = !disabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (index == 2) .1f else .035f)),
                border = androidx.compose.foundation.BorderStroke(.6.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f)),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) { Text(text, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

@Composable private fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .25f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .12f),
    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = .08f),
    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .27f),
)

@Composable private fun LocalImage(path: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1300) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    val b = bitmap
    if (b != null) Image(b, contentDescription = "文章原图", modifier = modifier, contentScale = ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("载入原图…", style = MaterialTheme.typography.bodySmall) }
}

private fun stageLabel(stage: String) = when (stage) { "images" -> "等待识别"; "confirm" -> "预览正文"; "analysis" -> "逐词解析"; else -> stage }

private class Speaker(private val context: android.content.Context) {
    private var ready = false
    private var engine: TextToSpeech? = null
    init { engine = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) { val result = engine?.setLanguage(Locale.US); ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED }
    } }
    fun say(text: String) {
        if (!ready || engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "suidu-word") == TextToSpeech.ERROR)
            Toast.makeText(context, "系统英语语音尚未就绪，请检查 TTS 设置", Toast.LENGTH_SHORT).show()
    }
    fun close() { engine?.stop(); engine?.shutdown() }
}
