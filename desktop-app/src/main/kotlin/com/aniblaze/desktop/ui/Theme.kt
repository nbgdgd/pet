package com.aniblaze.desktop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AniBlaze desktop design system — the same "neon-glass on OLED" language as the
 * mobile app, tuned for a large-monitor pointer UI. Everything visual routes
 * through these tokens so the app reads as one cohesive premium product.
 */

/**
 * Тема оформления — набор поверхностей и текста. Токены ниже (`Surface1`,
 * `TextPrimary`, `AccentOrange`…) остались теми же именами, что и всегда, но
 * читают текущую тему из [AppThemeState]: сотня экранов не переписывалась, а
 * смена темы в настройках перерисовывает всё сразу.
 *
 * Пресеты — как в Telegram: классика (OLED-чёрный с фиолетовым отливом), AMOLED
 * (чистый чёрный), ночная (сине-серая), цветная (тёмно-фиолетовая), дневная
 * (светлая). Акцент выбирается отдельно и один на все темы.
 */
data class AppTheme(
    val key: String,
    val label: String,
    val light: Boolean,
    val background: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glassFill: Color,
    val glassFillStrong: Color,
    val glassBorder: Color,
    val scrim: Color,
    val tabStripBackground: Color,
    val tabStripDivider: Color,
    val tabHoverFill: Color,
    val tabPressedFill: Color,
) {
    companion object {
        val CLASSIC = AppTheme(
            key = "classic", label = "Классика", light = false,
            background = Color(0xFF000000), surface1 = Color(0xFF0C0B12), surface2 = Color(0xFF15141D),
            surface3 = Color(0xFF1F1E2B), surface4 = Color(0xFF2A2836),
            textPrimary = Color(0xFFF7F7FA), textSecondary = Color(0xFF9C9CAB), textTertiary = Color(0xFF5E5E6B),
            glassFill = Color(0x14FFFFFF), glassFillStrong = Color(0x24FFFFFF), glassBorder = Color(0x24FFFFFF),
            scrim = Color(0x99000000),
            tabStripBackground = Color(0xFF050409), tabStripDivider = Color(0xFF1A1926),
            tabHoverFill = Color(0x14FFFFFF), tabPressedFill = Color(0x0AFFFFFF),
        )
        val AMOLED = CLASSIC.copy(
            key = "amoled", label = "AMOLED",
            background = Color(0xFF000000), surface1 = Color(0xFF000000), surface2 = Color(0xFF0B0B0B),
            surface3 = Color(0xFF141414), surface4 = Color(0xFF1D1D1D),
            textSecondary = Color(0xFFA0A0A8), textTertiary = Color(0xFF60606A),
            tabStripBackground = Color(0xFF000000), tabStripDivider = Color(0xFF161616),
        )
        val NIGHT = CLASSIC.copy(
            key = "night", label = "Ночная",
            background = Color(0xFF0E1621), surface1 = Color(0xFF17212B), surface2 = Color(0xFF1E2A36),
            surface3 = Color(0xFF242F3D), surface4 = Color(0xFF2B3948),
            textPrimary = Color(0xFFF5F7FA), textSecondary = Color(0xFF9AA6B2), textTertiary = Color(0xFF5F6B78),
            tabStripBackground = Color(0xFF0B121A), tabStripDivider = Color(0xFF223040),
        )
        val TINTED = CLASSIC.copy(
            key = "tinted", label = "Цветная",
            background = Color(0xFF121016), surface1 = Color(0xFF1A1721), surface2 = Color(0xFF221E2C),
            surface3 = Color(0xFF2B2637), surface4 = Color(0xFF352F43),
            textSecondary = Color(0xFFA6A0B4), textTertiary = Color(0xFF6A647A),
            tabStripBackground = Color(0xFF0F0D13), tabStripDivider = Color(0xFF2A2536),
        )
        val DAY = AppTheme(
            key = "day", label = "Дневная", light = true,
            background = Color(0xFFF3F5F8), surface1 = Color(0xFFFFFFFF), surface2 = Color(0xFFF1F3F6),
            surface3 = Color(0xFFE6E9EE), surface4 = Color(0xFFDADEE5),
            textPrimary = Color(0xFF15171C), textSecondary = Color(0xFF596070), textTertiary = Color(0xFF8A90A0),
            glassFill = Color(0x14000000), glassFillStrong = Color(0x22000000), glassBorder = Color(0x22000000),
            scrim = Color(0x66000000),
            tabStripBackground = Color(0xFFE7EAF0), tabStripDivider = Color(0xFFD5D9E0),
            tabHoverFill = Color(0x14000000), tabPressedFill = Color(0x0A000000),
        )
        val ALL = listOf(CLASSIC, DAY, TINTED, NIGHT, AMOLED)
        fun of(key: String): AppTheme = ALL.firstOrNull { it.key == key } ?: CLASSIC
    }
}

/** Цвет акцента — один на все темы. Первый — фирменный «уголёк». */
data class AppAccent(val key: String, val label: String, val color: Color) {
    companion object {
        val ALL = listOf(
            AppAccent("orange", "Уголёк", Color(0xFFFF6A3D)),
            AppAccent("blue", "Синий", Color(0xFF3D8BFF)),
            AppAccent("cyan", "Голубой", Color(0xFF2DB7E6)),
            AppAccent("green", "Зелёный", Color(0xFF3CB371)),
            AppAccent("pink", "Розовый", Color(0xFFFF4D9D)),
            AppAccent("violet", "Фиолетовый", Color(0xFF9D5CFF)),
            AppAccent("red", "Красный", Color(0xFFE04B4B)),
            AppAccent("gold", "Золотой", Color(0xFFE0A83A)),
        )
        /** Свой цвет: ключ «#RRGGBB» — акцент любого оттенка, не только из набора. */
        fun of(key: String): AppAccent = ALL.firstOrNull { it.key == key }
            ?: parseHex(key)?.let { AppAccent(key, "Свой", it) }
            ?: ALL.first()

        /** «#RRGGBB» → цвет; иное — null. Слишком тёмный акцент невидим на тёмной теме, но это выбор человека. */
        fun parseHex(raw: String): Color? {
            val hex = raw.trim().removePrefix("#")
            if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return Color(0xFF000000L or hex.toLong(16))
        }
    }
}

/**
 * Текущая тема и акцент. Состояние Compose: чтение из композиции подписывает её
 * на смену, чтение из рисования (drawBehind, Canvas) берёт актуальное при кадре.
 */
object AppThemeState {
    var theme: AppTheme by androidx.compose.runtime.mutableStateOf(AppTheme.CLASSIC)
    var accent: Color by androidx.compose.runtime.mutableStateOf(AppAccent.ALL.first().color)

    fun apply(themeKey: String, accentKey: String) {
        theme = AppTheme.of(themeKey)
        accent = AppAccent.of(accentKey).color
    }
}

// --- Surfaces (deep OLED-friendly blacks with a faint violet cast) ---
val OledBlack: Color get() = AppThemeState.theme.background
val Surface1: Color get() = AppThemeState.theme.surface1
val Surface2: Color get() = AppThemeState.theme.surface2
val Surface3: Color get() = AppThemeState.theme.surface3
val Surface4: Color get() = AppThemeState.theme.surface4

// --- Brand accents ---
val AccentOrange: Color get() = AppThemeState.accent // ember (primary brand) — теперь выбираемый
val AccentPurple = Color(0xFF9D5CFF) // electric violet
val AccentPink = Color(0xFFFF4D9D)   // magenta bridge
val AccentCyan = Color(0xFF2DE2E6)   // cool highlight

// --- Text ---
val TextPrimary: Color get() = AppThemeState.theme.textPrimary
val TextSecondary: Color get() = AppThemeState.theme.textSecondary
val TextTertiary: Color get() = AppThemeState.theme.textTertiary
val ErrorRed = Color(0xFFFF453A)
val RatingGold = Color(0xFFFFC24B)

/** Галка «досмотрено». Зелёный, а не оранжевый: оранжевый в приложении означает
 *  «внимание сюда», а досмотренное — ровно наоборот, уже сделанное. */
val WatchedGreen = Color(0xFF4ADE80)

// --- Glass / overlays ---
val GlassFill: Color get() = AppThemeState.theme.glassFill
val GlassFillStrong: Color get() = AppThemeState.theme.glassFillStrong
val GlassBorder: Color get() = AppThemeState.theme.glassBorder
val Scrim: Color get() = AppThemeState.theme.scrim
val GlowOrange: Color get() = AppThemeState.accent.copy(alpha = 0.2f)
val GlowPurple = Color(0x339D5CFF)

/** The signature ember→magenta brand sweep used on primary actions and headers. */
val BrandGradient: Brush get() = Brush.linearGradient(listOf(AccentOrange, AccentPink))
val BrandGradientTri: Brush get() = Brush.linearGradient(listOf(AccentPurple, AccentPink, AccentOrange))

/** A soft top-to-bottom scrim for poster overlays (title legibility). */
val PosterScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))

// --- Shape scale ---
// --- Полоса вкладок ---
// Полоса ТЕМНЕЕ страницы, активная вкладка — цвета страницы: так она читается как её
// продолжение, а не как ещё одна кнопка. Неактивные прозрачны и проявляются только
// под курсором — с десятком открытых тайтлов это единственный способ не превратить
// верх окна в рябь.
val TabStripBackground: Color get() = AppThemeState.theme.tabStripBackground
val TabStripDivider: Color get() = AppThemeState.theme.tabStripDivider
val TabHoverFill: Color get() = AppThemeState.theme.tabHoverFill
val TabPressedFill: Color get() = AppThemeState.theme.tabPressedFill

/** Единая кривая движения для всего приложения: быстрый разгон, мягкая остановка. */
val MotionEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

object Shapes {
    val chip = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val cardLg = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

@OptIn(ExperimentalTextApi::class)
private val DesktopFont = FontFamily("Segoe UI")

private val AniBlazeTypography = Typography(
    headlineLarge = TextStyle(fontFamily = DesktopFont, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = DesktopFont, fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = DesktopFont, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = DesktopFont, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = DesktopFont, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = DesktopFont, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = DesktopFont, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = DesktopFont, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
private fun aniBlazeColors(): androidx.compose.material3.ColorScheme {
    // Читается в композиции: смена темы в настройках пересобирает схему Material.
    val theme = AppThemeState.theme
    val accent = AppThemeState.accent
    val base = if (theme.light) androidx.compose.material3.lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = if (theme.light) Color.White else theme.background,
        secondary = AccentPurple,
        onSecondary = theme.background,
        tertiary = AccentCyan,
        background = theme.surface1,
        onBackground = theme.textPrimary,
        surface = theme.surface2,
        onSurface = theme.textPrimary,
        surfaceVariant = theme.surface3,
        onSurfaceVariant = theme.textSecondary,
        outline = theme.glassBorder,
        error = ErrorRed,
    )
}

@Composable
fun AniBlazeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = aniBlazeColors(),
        typography = AniBlazeTypography,
        content = content,
    )
}

/**
 * An animated shimmer brush for skeleton placeholders — a soft light band sweeping
 * left-to-right across a muted surface, the standard "content loading" cue.
 */
@Composable
fun rememberShimmerBrush(widthPx: Float = 900f): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -widthPx,
        targetValue = widthPx * 2,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerTranslate",
    )
    return Brush.linearGradient(
        colors = listOf(Surface2, Surface4, Surface2),
        start = Offset(translate, 0f),
        end = Offset(translate + widthPx, 0f),
    )
}

/** A single shimmering placeholder block. */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = Shapes.card) {
    Column(modifier.clip(shape).background(rememberShimmerBrush())) {}
}

/** A poster-card skeleton (2:3 art + two title lines) for grid/row loading states. */
@Composable
fun PosterSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmerBrush()
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Shapes.card).background(shimmer),
        ) {}
        Column(Modifier.padding(top = 8.dp)) {
            Column(Modifier.fillMaxWidth(0.85f).height(11.dp).clip(Shapes.pill).background(shimmer)) {}
            Column(Modifier.padding(top = 6.dp).fillMaxWidth(0.55f).height(11.dp).clip(Shapes.pill).background(shimmer)) {}
        }
    }
}
