package com.aniblaze.desktop.pet

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl

/**
 * Звуки питомца — короткие и тихие, только на события, а не на каждую реплику:
 * предложение (вернуться / промотать эндинг), просьба оценить сезон, новая серия в
 * избранном. Обычная болтовня беззвучна намеренно.
 *
 * Звуки — из набора «Interface Sounds» Kenney (kenney.nl), лицензия CC0
 * (`resources/pet/sounds/LICENSE-kenney.txt`); сконвертированы в тихий моно-WAV на
 * −12 дБ. Воспроизведение — javax.sound без внешних зависимостей; ошибка звука
 * никогда не роняет интерфейс.
 */
object PetSounds {
    enum class Sound(val file: String) {
        SAY("say"),
        OFFER("offer"),
        RELEASE("release"),
        SAD("sad"),
    }

    /** Включены ли звуки и на какой громкости (0..1). Ставит App из настроек. */
    @Volatile var enabled: Boolean = true
    @Volatile var volume: Float = 0.5f

    private val clips = java.util.concurrent.ConcurrentHashMap<Sound, Clip>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "aniblaze-pet-sounds").apply { isDaemon = true }
    }

    fun play(sound: Sound) {
        if (!enabled || volume <= 0f) return
        executor.execute {
            runCatching {
                val clip = clips.getOrPut(sound) { load(sound) ?: return@execute }
                if (clip.isRunning) clip.stop()
                clip.framePosition = 0
                applyVolume(clip)
                clip.start()
            }.onFailure {
                com.aniblaze.desktop.player.PlayerDiagnostics.failure("pet.sound.${sound.file}", it)
            }
        }
    }

    private fun load(sound: Sound): Clip? {
        val stream = PetSounds::class.java.classLoader.getResourceAsStream("pet/sounds/${sound.file}.wav") ?: return null
        val audio = AudioSystem.getAudioInputStream(java.io.BufferedInputStream(stream))
        return AudioSystem.getClip().apply { open(audio) }
    }

    /** Громкость — в децибелах поверх уже тихого файла: 1.0 = как записано, 0.5 ≈ −12 дБ. */
    private fun applyVolume(clip: Clip) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val gain = clip.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val db = (20.0 * kotlin.math.log10(volume.coerceIn(0.01f, 1f).toDouble())).toFloat()
        gain.value = db.coerceIn(gain.minimum, gain.maximum)
    }
}
