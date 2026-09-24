package com.aniblaze.desktop.pet

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Атлас питомца: один декод на процесс, кадры рисуются прямоугольником из общей
 * картинки — отдельные Bitmap не режем.
 */
object PetAtlas {
    const val COLS = 8
    const val ROWS = 9

    private val cache = HashMap<String, ImageBitmap?>()

    @Synchronized
    fun get(pet: PetDef): ImageBitmap? = cache.getOrPut(pet.id) {
        runCatching {
            val stream = PetAtlas::class.java.classLoader.getResourceAsStream(pet.spritesheetPath)
                ?: return@runCatching null
            stream.use { androidx.compose.ui.res.loadImageBitmap(it) }
        }.getOrNull()
    }

    fun cellWidth(bmp: ImageBitmap): Int = bmp.width / COLS
    fun cellHeight(bmp: ImageBitmap): Int = bmp.height / ROWS

    private val floors = HashMap<String, IntArray>()
    /** Visible feet/resting edge, cached once; no resizing of individual frames. */
    @Synchronized fun floors(pet: PetDef, bmp: ImageBitmap): IntArray = floors.getOrPut(pet.id) {
        val pixels = bmp.toPixelMap()
        val w = cellWidth(bmp); val h = cellHeight(bmp)
        IntArray(COLS * ROWS) { cell ->
            val x = (cell % COLS) * w; val y = (cell / COLS) * h
            (h - 1 downTo 0).firstOrNull { dy -> (0 until w).any { dx -> pixels[x + dx, y + dy].alpha >= .5f } } ?: h - 1
        }
    }
}

/**
 * Анимированный питомец.
 *
 * Кадры идут по [PetClip] с его длительностями, поверх — «дыхание» (±1.5 % по высоте
 * от нижней точки), как в TimePet: живость даёт оно, а не частая смена кадров.
 * Одноразовый клип (взмах, прыжок) доигрывается и возвращает к базовому действию.
 *
 * Анимация крутится ТОЛЬКО пока композиция жива и [animate] true: невидимый питомец
 * (свёрнут, скрыт в плеере) не тратит кадры.
 */
@Composable
fun PetSprite(
    pet: PetDef,
    action: PetAction,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** Темп анимации: 2 — вдвое быстрее (скорость x2), 0.5 — нарочито медленно. */
    tempo: Float = 1f,
) {
    val atlas = remember(pet.id) { PetAtlas.get(pet) } ?: return
    val floors = remember(pet.id) { PetAtlas.floors(pet, atlas) }
    val tempoNow by rememberUpdatedState(tempo.coerceIn(0.25f, 4f))
    val clip = remember(pet.id, action) { pet.clip(action) }
    var frame by remember(pet.id, action) { mutableStateOf(0) }
    var breath by remember { mutableStateOf(1f) }
    // Одноразовый клип отыграл — дальше держим последний кадр, пока состояние не сменят.
    var finished by remember(pet.id, action) { mutableStateOf(false) }

    LaunchedEffect(pet.id, action, animate) {
        if (!animate) { frame = clip.still; breath = 1f; return@LaunchedEffect }
        frame = 0
        finished = false
        var frameStarted = withFrameMillis { it }
        val breathStart = frameStarted
        while (true) {
            val now = withFrameMillis { it }
            // Дыхание: спящий дышит медленнее.
            val period = if (action == PetAction.SLEEP) 3200.0 else 2000.0
            breath = 1f + 0.015f * sin((now - breathStart) / period * 2 * Math.PI).toFloat()
            if (!finished && now - frameStarted >= (clip.durations[frame] / tempoNow).toLong()) {
                frameStarted = now
                val next = frame + 1
                if (next < clip.frames.size) {
                    frame = next
                } else if (clip.loop) {
                    frame = 0
                } else {
                    finished = true
                }
            }
        }
    }

    Canvas(modifier) {
        val cw = PetAtlas.cellWidth(atlas)
        val ch = PetAtlas.cellHeight(atlas)
        val fit = min(size.width / cw, size.height / ch)
        val dw = cw * fit
        val dh = ch * fit
        val left = (size.width - dw) / 2f
        val index = frame.coerceIn(0, clip.frames.size - 1)
        val grounded = action in setOf(PetAction.SLEEP, PetAction.SIT, PetAction.TIRED, PetAction.EXHAUSTED)
        val offset = if (grounded) floors[0] - floors[clip.rowAt(index) * PetAtlas.COLS + clip.frames[index]] else 0
        val top = size.height - dh + offset * fit
        val srcX = clip.frames[index] * cw
        val srcY = clip.rowAt(index) * ch
        // Масштаб от ступней: питомец «дышит», а не подпрыгивает.
        scale(scaleX = 1f + (breath - 1f) * 0.4f, scaleY = breath, pivot = androidx.compose.ui.geometry.Offset(size.width / 2f, top + dh)) {
            if (clip.mirror) {
                scale(scaleX = -1f, scaleY = 1f, pivot = androidx.compose.ui.geometry.Offset(size.width / 2f, top + dh / 2f)) {
                    drawFrame(atlas, srcX, srcY, cw, ch, left, top, dw, dh)
                }
            } else {
                drawFrame(atlas, srcX, srcY, cw, ch, left, top, dw, dh)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrame(
    atlas: ImageBitmap,
    srcX: Int,
    srcY: Int,
    cw: Int,
    ch: Int,
    left: Float,
    top: Float,
    dw: Float,
    dh: Float,
) {
    drawImage(
        image = atlas,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(cw, ch),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}
