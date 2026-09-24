package com.aniblaze.desktop

import com.aniblaze.aggregator.model.Anime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Дневные копии состояния: одна в день, восстановление с проверкой файла. */
class StateBackupsTest {
    @Test fun `копия появляется при запуске, восстановление возвращает избранное`() {
        val dir = Files.createTempDirectory("aniblaze-backups").toFile()
        val file = dir.resolve("state.json")
        val first = AppSettings(file)
        first.toggleFavorite(Anime(id = "ya:1", title = "Один", poster = ""))
        assertTrue(first.flush())
        // Второй запуск с тем же файлом — копия за сегодня.
        val second = AppSettings(file)
        val backups = second.backups()
        assertEquals(1, backups.size)
        assertTrue(backups.single().name.startsWith("state-" + java.time.LocalDate.now().toString()))
        // Всё стёрли — и вернули из копии.
        second.clearFavorites()
        assertTrue(second.state.value.favorites.isEmpty())
        assertTrue(second.restoreFrom(backups.single()))
        assertEquals(listOf("ya:1"), second.state.value.favorites.map { it.id })
        assertTrue(dir.resolve("backups/state-before-restore.json").exists(), "текущее состояние отложено перед заменой")
        // Битая копия ничего не меняет.
        val broken = dir.resolve("backups/state-2000-01-01.json").apply { writeText("{not json") }
        assertFalse(second.restoreFrom(broken))
        assertEquals(listOf("ya:1"), second.state.value.favorites.map { it.id })
    }

    @Test fun `экспорт JSON и CSV - файл состояния копируется, библиотека в таблице с BOM и кавычками`() {
        val dir = Files.createTempDirectory("aniblaze-export").toFile()
        val s = AppSettings(dir.resolve("state.json"))
        s.toggleFavorite(Anime(id = "ya:1", title = "Тайтл; с точкой", poster = "", year = 2024))
        s.setRating(Anime(id = "ya:2", title = "Второй", poster = ""), 4)
        val json = dir.resolve("out.json")
        assertTrue(s.exportState(json))
        assertEquals(listOf("ya:1"), AppSettings(json).state.value.favorites.map { it.id })

        val csv = libraryCsv(s.state.value)
        assertTrue(csv.startsWith("\uFEFFраздел;id;название;год;оценка;просмотрено серий;последний просмотр\r\n"))
        assertTrue("избранное;ya:1;\"Тайтл; с точкой\";2024;0;0;" in csv, csv)
        assertTrue("оценка;ya:2;Второй;;4;0;" in csv, csv)
        val out = dir.resolve("lib.csv")
        assertTrue(s.exportCsv(out))
        assertEquals(csv, out.readText())
    }
}
