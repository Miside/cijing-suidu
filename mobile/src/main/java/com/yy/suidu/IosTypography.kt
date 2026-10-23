@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package com.yy.suidu

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** Latin UI text uses the bundled Inter; missing glyphs use Android's system fallback. */
val IosUiFont = FontFamily(listOf(400, 500, 600, 700).map { weight ->
    Font(R.font.inter_variable, weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})
