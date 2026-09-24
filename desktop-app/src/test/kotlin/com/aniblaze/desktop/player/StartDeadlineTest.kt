package com.aniblaze.desktop.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Отсрочка приговора «поток не начался», пока хоронится прошлый экземпляр.
 *
 * ЗДЕСЬ БЫЛ ТУПИК, ИЗ КОТОРОГО ПРИЛОЖЕНИЕ НЕ ВЫХОДИЛО. Отсрочка давалась по одному
 * признаку «идут похороны» и потому была бесконечной: хоронят вызовом release() на
 * зависшей libVLC, а он на ней не возвращается никогда. Счётчик похорон оставался
 * ненулевым, отсчёт сбрасывался на каждом тике, приговор не мог быть вынесен ни при
 * каких условиях — и лестница попыток (свежая ссылка → другая озвучка → другой
 * источник) не трогалась вовсе.
 *
 * В журнале 17 августа это выглядело так:
 *
 *     01:09:37.8  vlc.abandon  generation=1
 *     01:09:47.4  vlc.abandon  generation=2
 *     дальше ни одной play.neverStarted — «Загрузка… 0 %» до убийства приложения
 */
class StartDeadlineTest {

    @Test
    fun `пока хоронят — отсрочка даётся`() {
        assertTrue(extendStartDeadline(reaperBusy = true, grantedNanos = 0L))
        assertTrue(extendStartDeadline(reaperBusy = true, grantedNanos = 5_000_000_000L))
    }

    @Test
    fun `отсрочка КОНЕЧНА`() {
        // Двадцать секунд — измеренный потолок честных похорон. Дальше судим новый
        // экземпляр без оглядки на покойника: лучше зря сменить озвучку, чем висеть
        // бесконечно.
        assertFalse(extendStartDeadline(reaperBusy = true, grantedNanos = 20_000_000_000L))
        assertFalse(extendStartDeadline(reaperBusy = true, grantedNanos = 60_000_000_000L))
    }

    @Test
    fun `никого не хоронят — отсрочки нет`() {
        assertFalse(extendStartDeadline(reaperBusy = false, grantedNanos = 0L))
    }
}
