package com.aniblaze.aggregator.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фильтр «главный герой» — по тексту карточки. Описания — настоящие, с YummyAnime
 * (обрезаны до нескольких предложений), чтобы пороги проверялись на реальности.
 */
class ProtagonistTest {
    private fun card(title: String, description: String) = Anime(id = "ya:1", title = title, poster = "", description = description)

    private val deathNote = card(
        "Тетрадь Смерти",
        "Устав от увядающего мира и немногословия своих собратьев, бог смерти Рюк отправляет на Землю тетрадь смерти, " +
            "желая поглядеть, что из этого выйдет. Его замысел начинает воплощаться, когда её подбирает гениальный " +
            "старшеклассник Лайт Ягами, которому так же наскучило окружающее общество. Изначально Лайт воспринял тетрадь " +
            "как чью-то глупую шутку, но вскоре экспериментальным путём открыл пугающую истину.",
    )
    private val onePunch = card(
        "Ванпанчмен",
        "Их покой охраняет наш главный герой, лысый парень по фамилии Сайтама с выражением вселенской тоски на лице. " +
            "Сайтама невероятно силён. Три года назад, потеряв работу, он так усердно тренировался, что теперь побеждает " +
            "любого монстра с одного удара.",
    )
    private val violet = card(
        "Вайолет Эвергарден",
        "Вайолет Эвергарден, молодая девушка, чья жизнь — не что иное, как война, послушно служит под командованием майора. " +
            "После серьёзных увечий, оставивших её без рук, она покинула поле боя и была взята под опеку бывшим командующим.",
    )
    private val naruto = card(
        "Наруто",
        "Это история, в которой рассказывается про мальчика-ниндзя. Он мечтает стать Хокаге: главой своей деревни. " +
            "Но Хокаге – это самый мудрый и сильный ниндзя деревни, поэтому парень попытается преодолеть кучу испытаний.",
    )
    private val kaguya = card(
        "Госпожа Кагуя: в любви как на войне",
        "Миюки Сироганэ — признанный молодой гений, имеющий высокие оценки по всем предметам и возглавляющий студенческий совет.",
    )
    private val demonSlayer = card(
        "Клинок, рассекающий демонов",
        "Легенда гласит, что существует убийца демонов, который рыщет под ночным небом, истребляя кровожадных демонов. " +
            "Танджиро Камадо — старший сын в семье. Вернувшись утром, парень обнаруживает, что вся его родня была зверски убита.",
    )
    private val overlord = card(
        "Оверлорд",
        "Момонга остаётся в теле своего персонажа — могущественного нежить-повелителя. Он решает захватить этот мир и " +
            "безжалостно устраняет всех, кто встаёт на пути, — тёмный властелин, которого боятся королевства.",
    )

    @Test fun `умный герой - Тетрадь смерти и Кагуя, но не Наруто`() {
        assertTrue(Protagonist.GENIUS.matches(deathNote), "score=${Protagonist.GENIUS.score(deathNote)}")
        assertTrue(Protagonist.GENIUS.matches(kaguya), "score=${Protagonist.GENIUS.score(kaguya)}")
        assertFalse(Protagonist.GENIUS.matches(naruto), "score=${Protagonist.GENIUS.score(naruto)}")
        assertFalse(Protagonist.GENIUS.matches(onePunch))
    }

    @Test fun `сильнейший - Ванпанчмен, но не Наруто и не Вайолет`() {
        assertTrue(Protagonist.OVERPOWERED.matches(onePunch), "score=${Protagonist.OVERPOWERED.score(onePunch)}")
        assertFalse(Protagonist.OVERPOWERED.matches(naruto), "score=${Protagonist.OVERPOWERED.score(naruto)}")
        assertFalse(Protagonist.OVERPOWERED.matches(violet))
    }

    @Test fun `антигерой - Оверлорд, но не Клинок`() {
        assertTrue(Protagonist.ANTIHERO.matches(overlord), "score=${Protagonist.ANTIHERO.score(overlord)}")
        assertFalse(Protagonist.ANTIHERO.matches(demonSlayer), "score=${Protagonist.ANTIHERO.score(demonSlayer)}")
    }

    @Test fun `героиня - Вайолет, но не Ванпанчмен`() {
        assertTrue(Protagonist.HEROINE.matches(violet), "score=${Protagonist.HEROINE.score(violet)}")
        assertFalse(Protagonist.HEROINE.matches(onePunch), "score=${Protagonist.HEROINE.score(onePunch)}")
        assertFalse(Protagonist.HEROINE.matches(naruto))
    }

    @Test fun `без описания судить не о чем - мимо, а фильтр даёт чип`() {
        assertFalse(Protagonist.HEROINE.matches(card("Она", "")))
        assertFalse(Protagonist.OVERPOWERED.matches(card("Сильнейший", "")))
        val f = CatalogFilter(protagonist = "genius")
        assertTrue(f.matches(deathNote) { emptySet() })
        assertFalse(f.matches(naruto) { emptySet() })
        assertTrue(f.chips().single().label == "Умный герой")
        assertTrue(f.activeCount == 1)
    }
}
