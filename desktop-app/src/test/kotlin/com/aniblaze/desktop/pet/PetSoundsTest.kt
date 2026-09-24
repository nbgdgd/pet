package com.aniblaze.desktop.pet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Звуки питомца лежат в ресурсах, читаются javax.sound и короткие (не мешают). */
class PetSoundsTest {
    @Test fun `все звуки на месте, моно, короче секунды, с лицензией рядом`() {
        PetSounds.Sound.entries.forEach { sound ->
            val stream = javaClass.classLoader.getResourceAsStream("pet/sounds/${sound.file}.wav")
            assertNotNull(stream, "нет звука ${sound.file}")
            val audio = javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.BufferedInputStream(stream))
            val format = audio.format
            assertEquals(1, format.channels, "${sound.file}: должен быть моно")
            val seconds = audio.frameLength / format.frameRate
            assertTrue(seconds in 0.05f..1.0f, "${sound.file}: ${seconds}s — звук должен быть коротким")
        }
        val license = javaClass.classLoader.getResourceAsStream("pet/sounds/LICENSE-kenney.txt")
        assertNotNull(license)
        assertTrue(license.use { String(it.readBytes()) }.contains("CC0"))
    }

    @Test fun `выключенные звуки не пытаются играть`() {
        PetSounds.enabled = false
        // Ни исключений, ни обращений к аудиоустройству: метод просто выходит.
        PetSounds.play(PetSounds.Sound.SAY)
        PetSounds.enabled = true
        PetSounds.volume = 0f
        PetSounds.play(PetSounds.Sound.SAY)
        PetSounds.volume = 0.5f
    }
}
