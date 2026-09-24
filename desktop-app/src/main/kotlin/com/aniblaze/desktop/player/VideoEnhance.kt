package com.aniblaze.desktop.player

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Улучшение картинки без обращения к источникам — прямо на выводе кадра.
 *
 * Зачем: аниме почти всегда приходит 720p, а окно давно 1080p и выше, поэтому кадр
 * растягивается и линии мылятся. Взять картинку лучше неоткуда — значит надо лучше
 * показывать ту, что есть.
 *
 * Почему именно так. Общепринятое решение для аниме — [Anime4K](https://github.com/bloc97/Anime4K),
 * но это набор GLSL-шейдеров для mpv/MPC: libVLC чужие шейдерные цепочки не
 * подключает, а его собственный фильтр `sharpen` — обычная нерезкая маска, которая
 * на плоских заливках аниме даёт «звон» по контурам. Зато в режиме наложения кадры
 * рисуем МЫ, а Skia умеет SkSL — туда шейдер встраивается штатно.
 *
 * Что делает шейдер: контрастно-адаптивная резкость (идея AMD CAS). Сила подъёма
 * считается ОТДЕЛЬНО ДЛЯ КАЖДОГО ПИКСЕЛЯ из локального контраста: у чёткой границы
 * запас до чёрного и белого мал, значит и добавка мала — ореолов не будет; на
 * размытой линии запас велик, и она собирается обратно. Плоские заливки, которых в
 * аниме большинство, не трогаются вовсе — именно поэтому CAS уместнее нерезкой
 * маски, которая усиливает всё подряд вместе с шумом компрессии.
 */
object VideoEnhance {

    /**
     * Насколько сильно поднимать резкость.
     *
     * Числа ЗАМЕРЕНЫ на настоящих кадрах аниме (эталон, ужатый в 1.5 раза, — как
     * 720p в окне 1080p — и восстановление обратно):
     *
     *     без резкости        PSNR 40.95  SSIM 0.9606
     *     сила 0.35           PSNR 41.25  SSIM 0.9620   ← лучший результат
     *     сила 0.65           PSNR 40.67  SSIM 0.9581
     *     сила 1.00           PSNR 35.84  SSIM 0.9096
     *
     * То есть выше 0.35 картинка становится ХУЖЕ, а не резче: перебор съедает
     * структуру. Поэтому «Сильно» — это ровно измеренный оптимум, а не край шкалы.
     */
    enum class Level(
        val key: String,
        val label: String,
        /** Сила GPU CAS-фильтра в Compose-рендерере. */
        val strength: Float,
        /** Сила штатного VLC sharpen; null полностью убирает фильтр. */
        val nativeSigma: Float?,
    ) {
        OFF("off", "Выключено", 0f, null),
        LIGHT("light", "Умеренно", 0.2f, 0.05f),
        STRONG("strong", "Сильно", 0.35f, 0.12f),
        ;

        companion object {
            fun of(key: String): Level = entries.firstOrNull { it.key == key } ?: OFF
        }
    }

    /**
     * Соседние пиксели берутся со смещением РОВНО В ОДИН ПИКСЕЛЬ.
     *
     * Здесь была ошибка ценой всей затеи: смещение задавалось как `1/ширина`, потому
     * что в шейдерах координаты обычно нормированы. В SkSL-фильтре они В ПИКСЕЛЯХ,
     * поэтому шаг получался около одной тысячной пикселя — все четыре «соседа»
     * оказывались тем же самым пикселем, формула сворачивалась в тождество, и фильтр
     * возвращал кадр байт в байт. Стенд это и вскрыл: любая сила давала совпадающий
     * до последнего знака результат.
     */
    private const val CAS_SKSL = """
uniform shader image;
uniform float strength;

half4 main(float2 xy) {
    half4 c = image.eval(xy);
    half3 n = image.eval(xy + float2( 0.0, -1.0)).rgb;
    half3 s = image.eval(xy + float2( 0.0,  1.0)).rgb;
    half3 w = image.eval(xy + float2(-1.0,  0.0)).rgb;
    half3 e = image.eval(xy + float2( 1.0,  0.0)).rgb;

    half3 lo = min(min(min(n, s), min(w, e)), c.rgb);
    half3 hi = max(max(max(n, s), max(w, e)), c.rgb);

    // Запас до чёрного и до белого: чем он меньше, тем ближе пиксель к готовой
    // границе и тем меньше добавка — так ореолы не появляются.
    half3 headroom = min(lo, half3(2.0) - hi);
    half3 amount = sqrt(clamp(headroom / max(hi, half3(0.0001)), 0.0, 1.0));
    half3 weight = -amount * half(strength) * 0.2;

    half3 sum = c.rgb + (n + s + w + e) * weight;
    half3 norm = half3(1.0) + 4.0 * weight;
    return half4(clamp(sum / max(norm, half3(0.0001)), 0.0, 1.0), c.a);
}
"""

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(CAS_SKSL) }
            .onFailure { PlayerDiagnostics.failure("enhance.compile.failed", it) }
            .getOrNull()
    }

    /**
     * Эффект для слоя, в котором нарисован кадр: резкость поднимается у того
     * изображения, которое видит глаз, — уже после растяжения на окно.
     *
     * null означает «ничего не делать» — и когда улучшение выключено, и когда шейдер
     * не собрался: остаться без картинки из-за украшательства нельзя.
     */
    fun renderEffect(level: Level): RenderEffect? = imageFilter(level)?.asComposeRenderEffect()

    /**
     * Опции для нативного video output libVLC.
     *
     * В нативном режиме кадр не проходит через Compose, поэтому используется
     * поставляемый вместе с VLC фильтр `sharpen`. Значения намеренно находятся у
     * самого нижнего края его диапазона 0..2: 0.05 — штатная аккуратная сила VLC,
     * 0.12 заметнее, но ещё не рисует яркие ореолы на аниме-контурах.
     */
    internal fun vlcOptions(level: Level): List<String> {
        val sigma = level.nativeSigma ?: return emptyList()
        return listOf(
            ":video-filter=sharpen",
            String.format(java.util.Locale.ROOT, ":sharpen-sigma=%.3f", sigma),
        )
    }

    /**
     * Тот же фильтр, но в терминах Skia — без обёртки Compose.
     *
     * Нужен стенду: применить его к картинке вне композиции и ЗАМЕРИТЬ результат.
     * Однажды этот шейдер оказался полным тождеством (см. комментарий выше), и
     * заметил это только замер — на глаз разница между «работает» и «не делает
     * ничего» неотличима.
     */
    internal fun imageFilter(level: Level): ImageFilter? {
        if (level == Level.OFF) return null
        val compiled = effect ?: return null
        return runCatching {
            val builder = RuntimeShaderBuilder(compiled)
            builder.uniform("strength", level.strength)
            ImageFilter.makeRuntimeShader(builder, "image", null)
        }.onFailure { PlayerDiagnostics.failure("enhance.build.failed", it) }.getOrNull()
    }
}
