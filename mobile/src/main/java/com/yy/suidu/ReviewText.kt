package com.yy.suidu

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/** Same serif face as the printed original; lookup is locked until the answer is revealed. */
@Composable
internal fun ReviewSentence(card: ReviewCard, revealed: Boolean, color: Color, modifier: Modifier, onWord: (Int) -> Unit) {
    AndroidView(modifier = modifier, factory = { ReadingTextView(it) }, update = { view ->
        view.setTextIsSelectable(false)
        view.tapLookupEnabled = revealed
        view.isLongClickable = false
        view.onWord = if (revealed) onWord else { _ -> }
        view.onTranslate = { _, _ -> }
        view.typeface = Typeface.SERIF
        view.setTextColor(color.toArgb())
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        view.setLineSpacing(view.textSize * .60f, 1f)
        val text = SpannableString(card.context)
        if (card.targetStart in 0 until card.targetEnd && card.targetEnd <= text.length) {
            text.setSpan(StyleSpan(Typeface.BOLD), card.targetStart, card.targetEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(UnderlineSpan(), card.targetStart, card.targetEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (view.text.toString() != card.context || view.tag != (card.targetStart to card.targetEnd)) {
            view.setText(text, TextView.BufferType.SPANNABLE)
            view.tag = card.targetStart to card.targetEnd
        }
    })
}

/** The last request waits for TTS initialization; leaving the screen discards pending speech. */
internal class WordSpeechQueue {
    var initialized = false
        private set
    private var pending: String? = null
    val hasPending: Boolean get() = pending != null
    fun request(text: String): String? = if (initialized) text else { pending = text; null }
    fun ready(): String? { initialized = true; return pending.also { pending = null } }
    fun stop() { pending = null }
}
