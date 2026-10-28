package com.yy.suidu

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/** Native selection handles and action menu; short taps retain contextual word lookup. */
internal class ReadingTextView(context: android.content.Context) : TextView(context) {
    var tapLookupEnabled = true
    var onWord: (Int) -> Unit = {}
    var onTranslate: (Int, Int) -> Unit = { _, _ -> }
    var highlighted = emptySet<Int>()
    var handleTint: Int? = null
    override fun requestRectangleOnScreen(rectangle: android.graphics.Rect, immediate: Boolean): Boolean =
        if (hasSelection()) super.requestRectangleOnScreen(rectangle, immediate) else false
    private var downX = 0f; private var downY = 0f; private var selecting = false; private var moved = false
    init {
        setTextIsSelectable(true)
        setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP)
        typeface = Typeface.SERIF
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, TRANSLATE, 0, "翻译").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != TRANSLATE) return false
                val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                val end = maxOf(selectionStart, selectionEnd).coerceAtMost(text.length)
                if (start < end) onTranslate(start, end)
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false; selecting = hasSelection() }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) + abs(event.y - downY) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
        }
        val shortTap = tapLookupEnabled && event.actionMasked == MotionEvent.ACTION_UP && !moved && !selecting &&
            event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
        val handled = super.onTouchEvent(event)
        if (shortTap) {
            val offset = getOffsetForPosition(event.x, event.y)
            post { onWord(offset) }
        }
        return handled || tapLookupEnabled
    }
    companion object { private const val TRANSLATE = 0x535549 }
}

@Composable
internal fun SelectableParagraph(paragraph: Paragraph, font: Float, textColor: Color, selectionColor: Color, handleColor: Color,
    selectedIds: Set<Int>, modifier: Modifier, onWord: (Int) -> Unit, onTranslate: (Int, Int) -> Unit, cloze: Boolean = false) {
    AndroidView(modifier = modifier, factory = { ReadingTextView(it) }, update = { view ->
        view.onWord = onWord; view.onTranslate = onTranslate
        view.setTextColor(textColor.toArgb()); view.highlightColor = selectionColor.toArgb()
        if (view.handleTint != handleColor.toArgb()) {
            view.handleTint = handleColor.toArgb()
            view.getTextSelectHandleLeft()?.mutate()?.let { it.setTint(handleColor.toArgb()); view.setTextSelectHandleLeft(it) }
            view.getTextSelectHandleRight()?.mutate()?.let { it.setTint(handleColor.toArgb()); view.setTextSelectHandleRight(it) }
            view.getTextSelectHandle()?.mutate()?.let { it.setTint(handleColor.toArgb()); view.setTextSelectHandle(it) }
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, font)
        view.setLineSpacing(view.textSize * .65f, 1f)
        // Equal-length display substitution preserves every saved UTF-16 word/selection offset.
        val display = if (cloze) ClozeCore.display(paragraph.text) else paragraph.text
        if (view.text.toString() != display) {
            view.setText(SpannableString(display), TextView.BufferType.SPANNABLE)
            view.highlighted = emptySet()
        }
        if (view.highlighted != selectedIds) {
            view.highlighted = selectedIds.toSet()
            (view.text as Spannable).apply {
                getSpans(0, length, BackgroundColorSpan::class.java).forEach { removeSpan(it) }
                getSpans(0, length, StyleSpan::class.java).forEach { removeSpan(it) }
                paragraph.tokens.filter { it.id in selectedIds }.forEach { token ->
                    val start = token.start - paragraph.start; val end = token.end - paragraph.start
                    setSpan(BackgroundColorSpan(selectionColor.toArgb()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    })
}
