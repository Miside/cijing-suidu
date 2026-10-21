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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
        }
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF0A84FF), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF152B45), onPrimaryContainer = Color(0xFFD6E9FF), secondary = Color(0xFF64D2FF),
        background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7), surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFFAEAEB2), surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHighest = Color(0xFF2C2C2E), error = Color(0xFFFF453A))
        else lightColorScheme(primary = Color(0xFF007AFF), onPrimary = Color.White,
            primaryContainer = Color(0xFFE6F1FF), onPrimaryContainer = Color(0xFF003F87), secondary = Color(0xFF32ADE6),
            background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF6C6C70), surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFE5E5EA), error = Color(0xFFFF3B30))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var navCompact by remember { mutableStateOf(false) }
    val scrollThreshold = with(LocalDensity.current) { 22.dp.toPx() }
    val dockScroll = remember(scrollThreshold) { object : NestedScrollConnection {
        private var accumulated = 0f
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && kotlin.math.abs(consumed.y) > .2f) {
                if (accumulated * consumed.y < 0f) accumulated = 0f
                accumulated += consumed.y
                if (kotlin.math.abs(accumulated) > scrollThreshold) navCompact = consumed.y < 0f
            }
            return Offset.Zero
        }
    } }
    LaunchedEffect(tab, state.reader?.article?.id) { navCompact = false }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, speaker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> speaker.foreground = true
                Lifecycle.Event.ON_STOP -> { speaker.foreground = false; speaker.stop() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); speaker.close() }
    }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) { snackbar.showSnackbar(state.message); model.clearMessage() }
    }
    LaunchedEffect(historyLemma) { historyLemma?.let { model.wordHistory(it) } ?: model.clearHistory() }
    BackHandler(enabled = state.reader != null || tab != 0) { if (state.reader != null) model.closeArticle() else tab = 0 }
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    ), shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))) {
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
            topBar = {
                if (state.reader != null) GlassHeader(true, model::closeArticle)
                else Spacer(Modifier.fillMaxWidth().statusBarsPadding().height(4.dp))
            },
            bottomBar = { if (state.reader == null && !keyboardVisible) GlassNavigation(tab, navCompact) { tab = it } },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).nestedScroll(dockScroll)
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
                        IosTextButton(onClick = model::cancel) { Text("暂停") }
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
                    3 -> ReviewScreen(state, model, speaker) { o -> lookup = o; model.lookup(o) }
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
            LiquidSheet(onDismissRequest = { lookup = null }) {
                Column(Modifier.fillMaxWidth().heightIn(max = wordSheetHeight).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.token.surface, fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IosTextButton(onClick = { speaker.say(o.token.surface) }) {
                            NavGlyph(5, MaterialTheme.colorScheme.primary, Modifier.size(21.dp))
                            Spacer(Modifier.width(6.dp)); Text("朗读", fontSize = 14.sp)
                        }
                    }
                    Text(o.lemma, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("本文释义", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(ReadingTools.meaningWithPos(o.meaning, o.pos), fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold)
                    } }
                    state.reader?.phrases?.filter { o.token.id in it.tokenIds }?.forEach { p ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text("${p.text}\n${p.meaning}", Modifier.padding(12.dp))
                        }
                    }
                    Text("其他常见释义", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    o.common.forEach { Text("• $it", fontSize = 16.sp, lineHeight = 23.sp) }
                    Text("AI 生成释义 · 来源：${o.articleTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RatingButtons(state.busy) { rating ->
                        model.rate(o, rating)
                        if (rating != 0) lookup = null
                    }
                    IosTextButton(onClick = { lookup = null; historyLemma = o.lemma }) { Text("查看这个词的所有出现位置") }
                    IosTextButton(onClick = { model.removeReview(o.lemma) }, enabled = !state.busy) { Text("将 ${o.lemma} 移出复习队列") }
                    WordQuestionPanel(o, state, model)
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        state.selectionTranslation?.let { translated ->
            LiquidSheet(onDismissRequest = model::dismissSelection) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("选段翻译", style = MaterialTheme.typography.headlineSmall)
                    SelectionContainer { Text(translated.selection.text, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 30.sp) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    if (translated.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在查找已保存译文或翻译选段…") }
                    else if (translated.error.isNotBlank()) {
                        Text(translated.error, color = MaterialTheme.colorScheme.error)
                        IosOutlineButton(onClick = { model.translateSelection(translated.selection) }, enabled = !state.busy) { Text("重试翻译") }
                    } else {
                        SelectionContainer { Text(translated.translation, style = MaterialTheme.typography.titleLarge, lineHeight = 30.sp) }
                        Text(translated.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IosTextButton(onClick = model::dismissSelection) { Text("返回文章") }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
        historyLemma?.let { lemma ->
            LiquidSheet(onDismissRequest = { historyLemma = null }) {
                val history = state.wordHistory.filter { it.lemma == lemma }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(lemma, style = MaterialTheme.typography.headlineMedium); Text("共 ${history.size} 次 · 同一释义的文字分组仅作参考", style = MaterialTheme.typography.bodySmall) }
                    item { IosTextButton(onClick = { model.removeReview(lemma) }, enabled = !state.busy) { Text("将 $lemma 移出复习队列") } }
                    items(history, key = { "${it.articleId}:${it.token.id}" }) { o ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${o.token.surface} · ${ReadingTools.meaningWithPos(o.meaning, o.pos)}", fontWeight = FontWeight.SemiBold)
                                Text(o.paragraphText, maxLines = 5, fontFamily = FontFamily.Serif)
                                IosTextButton(onClick = { historyLemma = null; model.openArticle(o.articleId) }) { Text("打开原文 · ${o.articleTitle}") }
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
                        item { IosTextButton(onClick = { gallery = null }) { Text("关闭原图") } }
                        items(paths.indices.toList()) { i -> Text("第 ${i + 1} 页", Modifier.padding(8.dp)); LocalImage(paths[i], Modifier.fillMaxWidth().height(480.dp)) }
                    }
                }
            }
        }
        deleteDraft?.let { d ->
            IosConfirmDialog("删除这份草稿？", "草稿正文、原图和已完成的解析批次将从本机删除。", "删除草稿", "保留", !state.busy,
                onDismiss = { deleteDraft = null }, onConfirm = { model.discard(d.id); deleteDraft = null })
        }
        deleteArticle?.let { a ->
            IosConfirmDialog("删除这篇文章？", "${a.title}\n正文和原图将从本机删除，词频会重新统计。只来自此文的复习卡会移除；其他文章仍有的同义项会保留复习进度。", "确认删除文章", "保留文章", !state.busy,
                onDismiss = { deleteArticle = null }, onConfirm = { model.deleteArticle(a.id); deleteArticle = null })
        }
        }
    }
}

@Composable private fun LibraryScreen(state: MobileState, open: (String) -> Unit, draft: (String) -> Unit, add: () -> Unit, delete: (Article) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            IosPageTitle("我的书库", java.text.SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()) + " · 阅读，让词汇生长") {
                GlassSurface(Modifier.size(44.dp), radius = 22.dp) {
                    IosTextButton(onClick = add, modifier = Modifier.fillMaxSize().semantics { contentDescription = "添加新文章" }, contentPadding = PaddingValues(0.dp)) {
                        Text("+", color = MaterialTheme.colorScheme.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
        }
        state.articles.firstOrNull()?.let { latest ->
            item { IosReadingFeature(latest.title, latest.words, { open(latest.id) }) }
        }
        if (!state.loaded) item { Text("正在读取本地词库…") }
        if (state.loaded && state.articles.isEmpty() && state.drafts.isEmpty()) item {
            IosPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IosEyebrow("YOUR READING COLLECTION")
                ReadingIllustration(Modifier.fillMaxWidth().height(172.dp))
                Text("你的书库还没有文章", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("收藏一篇想读的文章，\n从语境里积累每一个词。", Modifier.padding(top = 10.dp, bottom = 22.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GlassAction("导入文章图片", add, Modifier.fillMaxWidth())
                Text("从一篇文章，开始你的词库", Modifier.padding(top = 14.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(state.articles.size to "篇文章", state.vocabulary.size to "个词元", state.articles.sumOf { it.words } to "次遇见").forEachIndexed { i, (value, label) ->
                    if (i > 0) Box(Modifier.width(.5.dp).height(28.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)))
                    IosMetric(value.toString(), label, Modifier.weight(1f))
                }
            }
        }
        if (state.drafts.isNotEmpty()) item { IosSectionLabel("待处理草稿", "${state.drafts.size} 份") }
        items(state.drafts, key = { it.id }) { d ->
            IosPressable(Modifier.fillMaxWidth().animateItem(), { draft(d.id) }) { IosPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IosSymbolTile(1, Color(0xFFFF9500))
                    Column(Modifier.weight(1f)) {
                    Text(d.title, fontWeight = FontWeight.SemiBold)
                    Text("${d.images.size} 页 · ${stageLabel(d.stage)} · ${d.chunks}/${d.total} 批", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.error.isNotBlank()) Text(d.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    IosChevron()
                }
            } }
        }
        if (state.articles.isNotEmpty()) item { IosSectionLabel("全部文章", "${state.articles.size} 篇 · 已下载") }
        items(state.articles, key = { it.id }) { a ->
            IosPressable(Modifier.fillMaxWidth().animateItem(), { open(a.id) }) { IosPanel(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IosBookCover(a.title, Modifier.width(56.dp).height(78.dp), small = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(a.title, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${a.words} 词 · ${articleTime(a.created)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IosTextButton(onClick = { delete(a) }, enabled = !state.busy, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(44.dp)) {
                            Text("删除文章", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                    IosChevron()
                }
            } }
        }
        if (state.articles.isNotEmpty() || state.drafts.isNotEmpty()) item {
            IosOutlineButton(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("导入文章图片") }
        }
        item { Spacer(Modifier.height(116.dp)) }
    }
}

@Composable private fun ImportScreen(
    state: MobileState, draft: Draft?, model: MobileModel, newImages: () -> Unit,
    moreImages: (String) -> Unit, onSettings: () -> Unit, onGallery: (List<String>) -> Unit, onDelete: (Draft) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IosPageTitle("添加文章", "把每一次遇见，变成自己的语言")
        if (draft == null) {
            IosPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ReadingIllustration(Modifier.fillMaxWidth().height(148.dp))
                    Text("把想读的内容，留在这里。", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("拍下书页，或收藏文章截图。\n随读会为你整理正文和语境词义。", Modifier.padding(top = 10.dp),
                        fontSize = 14.sp, lineHeight = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IosImportJourney()
        }
        Text("每篇最多 12 张图片。先排页序，再识别正文。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassAction("新建文章 · 选择图片", newImages, Modifier.fillMaxWidth(), !state.busy)
        if (!state.configured) IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("先填写你的 DeepSeek API Key")
            IosTextButton(onClick = onSettings) { Text("前往设置") }
        } }
        if (draft != null) {
            key(draft.id) {
                Text("当前草稿 · ${stageLabel(draft.stage)}", style = MaterialTheme.typography.titleMedium)
                if (draft.error.isNotBlank()) IosPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("处理未完成", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        SelectionContainer { Text(draft.error, fontSize = 14.sp, lineHeight = 23.sp) }
                        Text("已停止自动请求。按原因修正后再继续，已完成内容保留。", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (draft.stage == "images") {
                    draft.images.forEachIndexed { i, path ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LocalImage(path, Modifier.width(90.dp).height(120.dp).clickable { onGallery(draft.images) })
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text("第 ${i + 1} 页", fontWeight = FontWeight.SemiBold)
                                    Row {
                                        IosTextButton(onClick = { model.orderImages(draft.id, i, i - 1) }, enabled = i > 0 && !state.busy) { Text("上移") }
                                        IosTextButton(onClick = { model.orderImages(draft.id, i, i + 1) }, enabled = i < draft.images.lastIndex && !state.busy) { Text("下移") }
                                    }
                                    Row {
                                        IosTextButton(onClick = { model.rotateImage(draft.id, i) }, enabled = !state.busy) { Text("旋转") }
                                        IosTextButton(onClick = { model.removeImage(draft.id, i) }, enabled = !state.busy) { Text("移除") }
                                    }
                                }
                            }
                        }
                    }
                    IosTextButton(onClick = { moreImages(draft.id) }, enabled = !state.busy && draft.images.size < 12) { Text("添加图片到这篇文章") }
                    Text("点“联网识别”会将这些图片发送至 DeepSeek，并产生 API 用量。原图也保存在本机。请保持应用运行；中断后可再次打开草稿。", style = MaterialTheme.typography.bodySmall)
                    IosTextButton(onClick = { model.enterManualText(draft.id) }, enabled = !state.busy) { Text("手动粘贴或录入正文") }
                    GlassAction("联网识别完整文章", { model.recognize(draft.id) }, Modifier.fillMaxWidth(), !state.busy && state.configured && draft.images.isNotEmpty())
                } else {
                    var title by rememberSaveable(draft.title) { mutableStateOf(draft.title) }
                    var text by rememberSaveable(draft.text) { mutableStateOf(draft.text) }
                    IosTextField(title, { title = it.take(200) }, label = "文章标题", singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    if (draft.notes.isNotBlank()) GlassCard { Text("识别疑点\n${draft.notes}", Modifier.padding(14.dp)) }
                    IosTextButton(onClick = { onGallery(draft.images) }) { Text("对照原图 · ${draft.images.size} 页") }
                    Text("这里可以修改漏字、错字和跨页衔接。每页之间暂留空行；需要时可合并。", style = MaterialTheme.typography.bodySmall)
                    IosTextField(text, { if (it.length <= LanguageCore.MAX_TEXT) text = it }, label = "完整正文",
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp), textStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp))
                    Text("已保存 ${draft.chunks}/${draft.total} 批。修改正文后重新解析；格式不合格时立即停止，保留已校验词位。", style = MaterialTheme.typography.bodySmall)
                    IosOutlineButton(onClick = { model.saveText(draft.id, title, text) }, enabled = !state.busy && title.isNotBlank() && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存正文") }
                    GlassAction(if (draft.stage == "analysis") "继续补齐解析" else "确认并逐词解析", { model.analyze(draft.id, title, text) }, Modifier.fillMaxWidth(), !state.busy && state.configured && title.isNotBlank() && text.isNotBlank())
                    Text("每个词位都会解析，包括常见功能词。已校验的词位逐次保存在本机，遗漏只补缺项；全部校验完成后加入书库和词频统计。", style = MaterialTheme.typography.bodySmall)
                }
                Text("已记录 API 用量：输入 ${draft.inputTokens} / 输出 ${draft.outputTokens} tokens。未收到响应的请求用量不在此计数，费用以 DeepSeek 账户为准。", style = MaterialTheme.typography.bodySmall)
                Text("服务端缓存命中 ${draft.cacheHitTokens} / 未命中 ${draft.cacheMissTokens} 输入 tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                IosTextButton(onClick = { onDelete(draft) }, enabled = !state.busy) { Text("删除这份草稿", color = MaterialTheme.colorScheme.error) }
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IosBadge("英文精读")
                Text("READ IN CONTEXT", fontSize = 10.sp, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(data.article.title, fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
            Text("入库于 ${articleTime(data.article.created)}", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${data.article.words} 词次 · 点词查看本文释义", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            Text("长按正文，拖动选择后点“翻译”", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                IosTextButton(onClick = { translated = !translated }) { Text(if (translated) "隐藏段落译文" else "显示段落译文") }
                if (data.images.isNotEmpty()) IosTextButton(onClick = images) { Text("查看原图") }
            }
        }
        items(paragraphs, key = { it.id }) { p ->
            IosPanel(Modifier.fillMaxWidth()) {
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
        IosPageTitle("我的词库", "${words.size} 个词元 · ${words.sumOf { it.count }} 次在文章中遇见") {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                NavGlyph(2, MaterialTheme.colorScheme.primary, Modifier.size(27.dp))
            }
        }
        IosSearchField(search, { search = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("按出现频率", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IosTextButton(onClick = { difficult = !difficult }) { Text(if (difficult) "✓ 只看不熟悉的词" else "只看不熟悉的词", fontSize = 13.sp) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ReadingIllustration(Modifier.size(140.dp))
                    Text(if (words.isEmpty()) "导入并解析文章后，词库会自动建立。" else "没有匹配的词。", Modifier.padding(20.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(filtered, key = { it.lemma }) { w ->
                IosPressable(Modifier.fillMaxWidth().animateItem(), { onWord(w.lemma) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.lemma, fontSize = 24.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                            Text("${w.articles} 篇文章 · 查词 ${w.clicks} 次${if (w.difficult > 0) " · 待巩固" else ""}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${w.count}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("次遇见", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IosChevron()
                    }
                    HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
                }
            }
            item { Spacer(Modifier.height(116.dp)) }
        }
    }
}

@Composable private fun ReviewScreen(state: MobileState, model: MobileModel, speaker: Speaker, onWord: (Occurrence) -> Unit) {
    val card = state.due.firstOrNull()
    var revealedFor by rememberSaveable { mutableStateOf<String?>(null) }
    // A new review of the same sense must establish a fresh answer/lookup lock.
    val cardSession = card?.let { "${it.id}:${it.reviews}:${it.articleId}:${it.tokenId}" }
    val revealed = cardSession != null && revealedFor == cardSession
    val reviewWords by produceState(emptyList<Occurrence>(), card?.articleId, card?.tokenId) {
        value = card?.let { model.reviewOccurrences(it) } ?: emptyList()
    }
    LaunchedEffect(card?.id, card?.reviews) {
        if (card == null) speaker.stop() else speaker.say(card.lemma)
    }
    DisposableEffect(speaker) { onDispose { speaker.stop() } }
    LaunchedEffect(card?.id, card?.context, revealed, state.busy) {
        if (card != null && revealed && !state.busy &&
            state.reviewTranslation?.key != LanguageCore.hash("${card.id}\u0000${card.context}")) model.translateReview(card)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IosPageTitle("今日复习", "回到原句，让记忆更清晰")
        if (!revealed) IosPanel(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth()) {
            IosMetric("${state.due.size}${if (state.due.size == 100) "+" else ""}", "当前到期", Modifier.weight(1f))
            IosMetric(state.reviewCount.toString(), "语境卡片", Modifier.weight(1f))
        } }
        if (card == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingIllustration(Modifier.size(160.dp), complete = true)
                Text("当前没有到期的复习", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("阅读时点开单词，标记“没记住 / 模糊 / 记住了”，就会建立带原文语境的复习卡。",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 15.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IosTextButton(onClick = model::refreshNow) { Text("刷新到期卡片") }
            }
        } else {
            IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("回想本文中的意思", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IosBadge("语境复习")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(card.lemma, modifier = Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold)
                IosTextButton(onClick = { speaker.say(card.lemma) }, contentPadding = PaddingValues(horizontal = 9.dp, vertical = 10.dp)) {
                    NavGlyph(5, MaterialTheme.colorScheme.primary, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp)); Text("朗读单词", fontSize = 13.sp)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReviewSentence(card, revealed, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth()) { offset ->
                    if (revealed) ReadingTools.reviewWord(card, offset, reviewWords)?.let(onWord)
                }
                Text((if (card.shortened) "原句节选" else "原文短句") + if (revealed) " · 点击单词查义" else " · 揭晓后可点词", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            } }
            Text("回想这个词在这句原文里的意思。", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.contextChoices > 1) Text("有 ${card.contextChoices} 个同义项原句，每完成 3 次复习适当换一句。", style = MaterialTheme.typography.bodySmall)
            AnimatedContent(revealed, transitionSpec = {
                (fadeIn(tween(220)) + androidx.compose.animation.scaleIn(spring(.86f, 480f), initialScale = .96f) togetherWith fadeOut(tween(100)))
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> spring(.9f, 450f) }))
            }, label = "review-answer") { answer ->
            if (!answer) GlassAction("显示本文释义", { revealedFor = cardSession; model.translateReview(card) }, Modifier.fillMaxWidth(), !state.busy, arrow = false)
            else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(ReadingTools.meaningWithPos(card.meaning, card.pos), style = MaterialTheme.typography.titleLarge)
                RatingButtons(state.busy) { rating -> model.rateCard(card, rating) }
                IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("这句的意思", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    val translation = state.reviewTranslation?.takeIf { it.key == LanguageCore.hash("${card.id}\u0000${card.context}") }
                    if (translation == null || translation.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备句子译文…") }
                    else if (translation.error.isNotBlank()) {
                        Text(translation.error, color = MaterialTheme.colorScheme.error)
                        IosTextButton(onClick = { model.translateReview(card) }, enabled = !state.busy) { Text("重试句子翻译") }
                    } else SelectionContainer { Text(translation.text, lineHeight = 27.sp) }
                } }
                card.common.forEach { Text("• $it") }
                Text("没记住：10 分钟后；模糊：1 天后；记住了：逐步延长至 1、3、7、14、30、60、120 天。", style = MaterialTheme.typography.bodySmall)
            }
            }
            IosTextButton(onClick = { model.openArticle(card.articleId) }) { Text("回到来源文章") }
            IosTextButton(onClick = { model.removeReview(card.lemma) }, enabled = !state.busy) { Text("将 ${card.lemma} 移出复习队列") }
            if (card.sourceTitle.isNotBlank()) Text(card.sourceTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(196.dp))
    }
}

private fun articleTime(time: Long) = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(time))

@Composable private fun WordQuestionPanel(word: Occurrence, state: MobileState, model: MobileModel) {
    var question by rememberSaveable(word.articleId, word.token.id) { mutableStateOf("") }
    IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("问 DeepSeek", style = MaterialTheme.typography.titleMedium)
        Text("结合这个词和原文解答", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("为什么是这个词义？", "解释这句的语法").forEach { prompt ->
                IosTextButton(onClick = { question = prompt; model.askWord(word, prompt) }, enabled = !state.busy,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)) { Text(prompt, fontSize = 11.sp) }
            }
        }
        IosTextField(question, { question = it.take(600) }, label = "关于这个词或文章的问题",
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IosPageTitle("设置与偏好", "让随读，更合你的习惯") {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .055f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                NavGlyph(4, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(24.dp))
            }
        }
        IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IosSymbolTile(1, IosPalette.Blue)
            Column(Modifier.weight(1f)) {
                Text("DeepSeek 连接", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (state.configured) "已保存 API Key（本机加密）" else "尚未填写 API Key", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IosTextField(key, { key = it }, singleLine = true, label = if (state.configured) "填写新的 API Key" else "粘贴 API Key", visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassAction("保存密钥", { model.saveKey(key); key = "" }, Modifier.weight(1f), !state.busy && key.isNotBlank(), arrow = false)
            IosOutlineButton(onClick = model::checkConnection, enabled = !state.busy && state.configured, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("验证连接") }
        }
        if (state.configured) IosTextButton(onClick = model::removeKey, enabled = !state.busy) { Text("移除本机密钥") }
        } }
        Text("图片与文字分析通过 DeepSeek 完成。入库后的文章、词库与复习保存在本机。", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IosSectionLabel("Token 与缓存")
        IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            IosMetric(state.usage[0].toString(), "输入 tokens", Modifier.weight(1f))
            IosMetric(state.usage[1].toString(), "输出 tokens", Modifier.weight(1f))
            IosMetric(state.usage[2].toString(), "缓存命中", Modifier.weight(1f))
        }
        Text("服务端未命中 ${state.usage[3]} tokens · 已有释义优先本地复用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IosTextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起节省说明" else "查看节省说明") }
        AnimatedVisibility(details, enter = fadeIn(tween(180)) + androidx.compose.animation.expandVertically(tween(250)), exit = fadeOut(tween(130)) + androidx.compose.animation.shrinkVertically(tween(220))) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("常见释义只为新词生成；相同图片、相同段落优先复用本机缓存；逐词输出采用紧凑格式，不携带长聊天历史。固定提示词与段落放在前部，变化的任务范围放在末尾。", style = MaterialTheme.typography.bodySmall)
            Text("DeepSeek 服务端缓存自动启用，命中由服务端决定，不能保证每次命中，也不会为预热缓存额外调用 API。数字来自实际响应，并非估算费用。", style = MaterialTheme.typography.bodySmall)
        }
        }
        } }
        IosSectionLabel("阅读外观")
        IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            IosSymbolTile(0, IosPalette.Indigo)
            Column(Modifier.weight(1f)) { Text("液态玻璃", fontWeight = FontWeight.SemiBold); Text("折射、触摸高光与悬浮导航", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IosSwitch(checked = glass, onCheckedChange = onGlass)
        }
        IosRowDivider()
        IosAppearancePicker(dark, onDark)
        IosRowDivider()
        Text("正文字号 ${font.toInt()} sp", fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f)) { IosFontSlider(value = font, onValueChange = onFont) }
            Text("A", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        } }
        IosSectionLabel("本地数据")
        IosPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IosSettingsHeading("保存在这台设备", 0, IosPalette.Green)
        Text("${state.articles.size} 篇文章 · ${state.vocabulary.size} 个词元 · ${state.reviewCount} 条复习卡", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IosOutlineButton(onClick = export, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出文章、词库和复习记录") }
        Text("导出为 JSON，不包含 API Key 和原图。当前版本尚不支持导入恢复。卸载应用会清除本机数据。", style = MaterialTheme.typography.bodySmall)
        } }
        Text("词境·随读\n${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(116.dp))
    }
}

@Composable private fun RatingButtons(disabled: Boolean, onRate: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("没记住", "模糊", "记住了").forEachIndexed { index, text ->
            IosOutlineButton(onClick = { onRate(index) }, enabled = !disabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = when (index) { 0 -> if (LocalGlassScene.current?.dark == true) Color(0xFFFF9F0A) else Color(0xFFB96500); 2 -> Color.White; else -> MaterialTheme.colorScheme.primary },
                    containerColor = when (index) { 0 -> IosPalette.Orange.copy(alpha = .10f); 2 -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.primary.copy(alpha = .08f) }),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) { Text(text, fontSize = 13.sp, maxLines = 1) }
        }
    }
}

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
    private val queue = WordSpeechQueue()
    private var failed = false
    private var closed = false
    var foreground = true
    private var engine: TextToSpeech? = null
    init { engine = TextToSpeech(context) { status ->
        // Post until the constructor has assigned engine, even with fast local initialization.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (closed) return@post
            val language = if (status == TextToSpeech.SUCCESS) engine?.setLanguage(Locale.US) else null
            failed = language == null || language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED
            if (failed) { val hadPending = queue.hasPending; queue.stop(); if (hadPending) reportUnavailable() }
            else queue.ready()?.takeIf { foreground }?.let(::speakReady)
        }
    } }
    private fun reportUnavailable() {
        Toast.makeText(context, "英语自动发音不可用，请在系统文字转语音设置中安装英语语音", Toast.LENGTH_LONG).show()
    }
    private fun speakReady(text: String) {
        if (foreground && engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "suidu-word") == TextToSpeech.ERROR) reportUnavailable()
    }
    fun say(text: String) {
        if (!foreground) return
        if (failed) { reportUnavailable(); return }
        queue.request(text)?.let(::speakReady)
    }
    fun stop() { queue.stop(); engine?.stop() }
    fun close() { closed = true; stop(); engine?.shutdown(); engine = null }
}
