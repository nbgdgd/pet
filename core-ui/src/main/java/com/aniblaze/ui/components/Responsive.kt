package com.aniblaze.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Adaptive sizing derived from the REAL width the device/window reports
 * (via BoxWithConstraints), not hardcoded breakpoints. This makes the layout
 * correct on every phone, tablet, foldable and split-screen window.
 *
 * Material 3 window-size reference: compact < 600dp, medium 600–840dp,
 * expanded ≥ 840dp.
 */

/**
 * Columns for a vertical poster grid (Catalog / Favorites / History) computed
 * from the measured width. A normal phone (~360dp) yields 3; larger screens
 * scale up. Clamped to [3, 8] so small phones keep the familiar 3-up layout and
 * huge displays don't shrink posters to nothing.
 */
fun posterGridColumns(maxWidth: Dp): Int =
    (maxWidth.value / 120f).toInt().coerceIn(3, 8)

/**
 * Resolves the column count for a poster grid: honour the user's explicit choice
 * from Settings (3–8) when set, otherwise fall back to the auto value measured
 * from screen width. [setting] == 0 means "Auto".
 */
fun resolveGridColumns(setting: Int, maxWidth: Dp): Int =
    if (setting in 3..8) setting else posterGridColumns(maxWidth)

/**
 * Width of one card in a horizontally-scrolling poster row (Home rows, "similar").
 * ~138dp on a phone — matching the original feel — and a touch wider on tablets,
 * always leaving the next card peeking to hint at scrollability.
 */
fun posterRowCardWidth(maxWidth: Dp, columns: Int = posterGridColumns(maxWidth)): Dp {
    val count = columns.coerceIn(3, 8)
    val horizontalPadding = 16.dp
    val spacing = 10.dp
    return ((maxWidth - horizontalPadding * 2 - spacing * (count - 1)) / count).coerceAtLeast(1.dp)
}

/**
 * Width of a "Continue watching" card — slightly larger than a normal row card
 * because it carries a progress bar underneath.
 */
fun continueCardWidth(maxWidth: Dp): Dp =
    (maxWidth.value / 2.4f).coerceIn(140f, 200f).dp

/**
 * Ширина одной ячейки в сетке постеров.
 *
 * Нужна затем, что карточка больше не мерит себя сама: BoxWithConstraints внутри
 * каждой карточки — это отдельная под-композиция и двойной проход измерения на штуку,
 * а на экране их два-три десятка. Экран и так знает свою ширину и число колонок,
 * поэтому ячейку считаем здесь один раз и передаём вниз.
 *
 * [horizontalPadding] — отступ по ОДНОЙ стороне, [spacing] — просвет между колонками.
 */
fun gridCellWidth(maxWidth: Dp, columns: Int, horizontalPadding: Dp, spacing: Dp): Dp {
    val cols = columns.coerceAtLeast(1)
    return ((maxWidth - horizontalPadding * 2 - spacing * (cols - 1)) / cols).coerceAtLeast(1.dp)
}

/** Max content width for single-column screens (search results, onboarding, player controls). */
val ReadableMaxWidth: Dp = 720.dp
