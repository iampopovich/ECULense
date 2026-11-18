package io.github.iampopovich.eculense.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Содержимое над клавиатурой. Экраны живут внутри Scaffold над нижним меню (edge-to-edge, adjustResize не сжимает окно),
 * поэтому imePadding() не подходит: отступ = на сколько клавиатура перекрывает нижний край самого экрана.
 */
@Composable
fun Modifier.aboveKeyboard(): Modifier {
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val imeBottom = WindowInsets.ime.getBottom(density)
    var gapBelow by remember { mutableIntStateOf(0) }
    val overlap = (imeBottom - gapBelow).coerceAtLeast(0)
    return this
        .onGloballyPositioned { gapBelow = windowHeight - it.boundsInWindow().bottom.toInt() }
        .padding(bottom = with(density) { overlap.toDp() })
}
