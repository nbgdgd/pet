package com.aniblaze.desktop.pet

import kotlin.test.*

class DrizzDialogueTest {
    @Test fun `all connected shared pools are replaced only for Drizz`() {
        val lines = DrizzPhrases.greetings + DrizzPhrases.pauses + DrizzPhrases.titles +
            PetEvent.entries.filter { it != PetEvent.FIDGET }.flatMap { PetPhrases.of(it) } +
            (1..5).mapNotNull { petRatingReaction(it)?.text }
        for (line in lines) {
            assertNotEquals(line, DrizzDialogue.text("drizz", line), "Unchanged Drizz phrase: $line")
            for (other in PetDef.ALL.filter { it.id != "drizz" }) {
                assertEquals(line, DrizzDialogue.text(other.id, line))
            }
        }
    }

    @Test fun `metadata remains literal including template like text and nested quotes`() {
        val title = "«Я рядом.» / [N] / ${'$'}1 \\ новая серия"
        assertEquals("Первая серия «$title». Посмотрим, что это за хуйня",
            DrizzDialogue.text("drizz", "Первая серия «$title». Посмотрим, что это"))
        assertEquals("«$title». О, что выбрали, еблан?",
            DrizzDialogue.text("drizz", "«$title». О, что выбрали?"))
        assertEquals("Вышла новая серия, блядь! В избранном: «$title», №12, уёбище",
            DrizzDialogue.text("drizz", "Вышла новая серия! В избранном: «$title», №12"))
        assertEquals("Теперь слушаем: $title, блядь",
            DrizzDialogue.text("drizz", "Теперь слушаем: $title"))
        assertEquals("Тебя не было 1 ч 2 мин, блядь. Вернуться на 4:21?",
            DrizzDialogue.text("drizz", "Тебя не было 1 ч 2 мин. Вернуться на 4:21?"))
    }

    @Test fun `greeting prefixes and both dynamic values are retained`() {
        assertEquals("Доброе утро, уёбище! Я рядом, сука.", DrizzDialogue.text("drizz", "Доброе утро! Я рядом."))
        assertEquals("Доброй ночи, пидарас. Я рядом, сука.", DrizzDialogue.text("drizz", "Доброй ночи. Я рядом."))
        assertEquals("Оценка каталога: 8,7/10, сука", DrizzDialogue.text("drizz", "Оценка каталога: 8,7/10"))
        assertEquals("0,5× — помедленнее? Мне так даже спокойнее, уёбок",
            DrizzDialogue.text("drizz", "0,5× — помедленнее? Мне так даже спокойнее"))
        assertEquals("Название без изменений", DrizzDialogue.text("drizz", "Название без изменений"))
    }
}
