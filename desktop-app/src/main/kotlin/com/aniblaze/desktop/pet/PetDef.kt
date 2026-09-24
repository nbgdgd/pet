package com.aniblaze.desktop.pet

/**
 * Питомцы перенесены из Android-приложения TimePet (C:/Users/no987/Documents/TimePet,
 * `app/src/main/java/com/timeoverlay/pet/pet/PetDef.kt` + `assets/pet`). Перенесены
 * данные — атласы и раскладка кадров по действиям; весь Android-код (View, Canvas,
 * SharedPreferences) заменён своим на Compose Desktop.
 *
 * Авторство графики (см. `resources/pet/<id>/pet.json` и LICENSE рядом):
 *  • Claude — XiangWang, github.com/xiangking/Claude-style-Codex-pet, MIT
 *    (текст лицензии: `resources/pet/claude/LICENSE`).
 *  • Eigenblob — галерея codex-pet.com/pets/eigenblob, автор не указан, лицензия на
 *    странице отсутствует: личное некоммерческое использование с указанием источника.
 *  • Drizz, Aqua Wisp — галерея codex-pet.com (pets/drizz, pets/aqua-wisp), автор и
 *    лицензия на страницах не указаны — то же правило, что у Eigenblob.
 *  • NezukoCoder — petdex.dev/pets/nezukocoder (#402), автор Miro H.; лицензия не
 *    указана. Фан-арт по «Клинку, рассекающему демонов».
 *
 * Три последних используют раскладку кадров Claude: их атласы рисовались по тому же
 * стандарту, и поведение просили «как у Claude». Своих раскладок у них нет.
 *
 * Стандарт атласа Codex-pet: 1536×1872, сетка 8×9, ячейка 192×208. Ряды:
 * 0 idle, 1 run-right, 2 run-left, 3 waving, 4 jumping, 5 failed, 6 waiting,
 * 7 running, 8 review. Смысл кадров у авторов разный, поэтому раскладка задаётся
 * для каждого питомца отдельно.
 */

/** Один клип поверх атласа: ряд, колонки и длительности кадров (мс). */
class PetClip(
    val row: Int,
    val frames: IntArray,
    val durations: LongArray,
    /** false — клип играет один раз и возвращает к базовому состоянию. */
    val loop: Boolean = true,
    private val rows: IntArray? = null,
    /** Кадр для режима без анимации. */
    val still: Int = 0,
    val mirror: Boolean = false,
) {
    init {
        require(frames.size == durations.size) { "frames/durations mismatch" }
        require(rows == null || rows.size == frames.size) { "rows/frames mismatch" }
        require(still in frames.indices) { "still out of range" }
    }

    fun rowAt(index: Int): Int = rows?.get(index) ?: row
}

/** Что питомец делает сейчас. Набор урезан до нужного в AniBlaze. */
enum class PetAction {
    IDLE, HAPPY, CELEBRATE, WAVE, JUMP, SAD, EXHAUSTED, SLEEP, TIRED, LOOK_AROUND, SIT,
}

data class PetPersonality(
    val fidgetMinutes: Int,
    val speechMultiplier: Int,
    val greeting: String,
    val pause: String,
    val titleReaction: String,
)

/** Описание питомца: атлас, цвет акцента, раскладка кадров. */
class PetDef(
    val id: String,
    val displayName: String,
    /** Кому принадлежит графика — показывается в настройках. */
    val credit: String,
    val accent: Long,
    private val clips: Map<PetAction, PetClip>,
) {
    val spritesheetPath: String get() = "pet/$id/spritesheet.webp"
    val personality: PetPersonality get() = PERSONALITIES.getValue(id.takeIf { it in PERSONALITIES } ?: "claude")

    /** Клип действия; чего нет — ближайшее по смыслу, чтобы не показать неверное. */
    fun clip(action: PetAction): PetClip = clips[action] ?: when (action) {
        PetAction.CELEBRATE -> clip(PetAction.JUMP)
        PetAction.EXHAUSTED -> clip(PetAction.SAD)
        PetAction.SLEEP -> clip(PetAction.EXHAUSTED)
        PetAction.TIRED, PetAction.LOOK_AROUND, PetAction.SIT -> clip(PetAction.IDLE)
        else -> clips.getValue(PetAction.IDLE)
    }

    companion object {
        private val PERSONALITIES = mapOf(
            "claude" to PetPersonality(4, 1, "Я рядом. Что посмотрим?", "Я подожду", "Можно посмотреть описание"),
            "eigenblob" to PetPersonality(2, 1, "Готов смотреть вместе!", "Перерыв? Отдыхаем!", "О, что выбрали?"),
            "drizz" to PetPersonality(3, 1, "Что у нас сегодня?", "Пока осмотрюсь", "Любопытно. Почитаем описание?"),
            "aqua-wisp" to PetPersonality(8, 2, "Устраивайся. Я рядом", "Не спеши, я подожду", "Выбирай спокойно"),
            "nezukocoder" to PetPersonality(6, 2, "Привет. Посмотрим вместе?", "Тихо подожду", "Я уже устроилась рядом"),
        )
        /**
         * Eigenblob: idle — 2 закрытые глаза (моргание), 3 прищур; waving 0 и 2 —
         * взмахи; jumping 0 присед, 1–2 в воздухе; failed 0–2 плачет, 3–5 лежит,
         * 6–7 грустит; waiting 2 сидит, 3 наклон; review 1–2, 4 прищур.
         */
        val EIGENBLOB = PetDef(
            id = "eigenblob",
            displayName = "Eigenblob",
            credit = "codex-pet.com · автор не указан",
            accent = 0xFF4DA3FF,
            clips = mapOf(
                PetAction.IDLE to PetClip(
                    0, intArrayOf(0, 1, 2, 3, 0, 4, 5),
                    longArrayOf(2600, 160, 120, 160, 2200, 220, 220),
                ),
                PetAction.HAPPY to PetClip(
                    0, intArrayOf(4, 4, 0, 1, 3, 0, 2, 0),
                    longArrayOf(1800, 1600, 600, 300, 400, 2000, 120, 800),
                    rows = intArrayOf(6, 6, 0, 4, 4, 0, 0, 0),
                ),
                PetAction.WAVE to PetClip(
                    3, intArrayOf(1, 3, 1, 3, 1), longArrayOf(200, 320, 200, 320, 240), loop = false,
                ),
                PetAction.JUMP to PetClip(
                    4, intArrayOf(0, 1, 2, 1, 0), longArrayOf(160, 220, 260, 220, 200), loop = false,
                ),
                PetAction.CELEBRATE to PetClip(
                    4, intArrayOf(0, 1, 2, 1, 0, 1, 2, 1, 0),
                    longArrayOf(150, 200, 240, 200, 180, 200, 240, 200, 260), loop = false,
                ),
                PetAction.SAD to PetClip(
                    5, intArrayOf(0, 1, 1, 0, 7, 6, 6, 7),
                    longArrayOf(900, 1100, 1100, 900, 900, 1100, 1100, 900),
                ),
                PetAction.EXHAUSTED to PetClip(
                    5, intArrayOf(2, 3, 4, 5, 5, 4, 3, 2),
                    longArrayOf(800, 900, 1200, 1400, 1400, 1200, 900, 800),
                ),
                PetAction.SLEEP to PetClip(5, intArrayOf(3, 4, 5, 4), longArrayOf(2200, 2200, 2200, 2200)),
                PetAction.TIRED to PetClip(
                    6, intArrayOf(0, 2, 2, 4, 4, 2, 0, 5),
                    longArrayOf(1000, 1200, 1200, 1400, 1400, 1200, 1000, 1400), still = 3,
                ),
                PetAction.LOOK_AROUND to PetClip(
                    8, intArrayOf(5, 2, 2, 5, 1, 5),
                    longArrayOf(400, 900, 900, 500, 900, 400), loop = false,
                ),
                PetAction.SIT to PetClip(
                    6, intArrayOf(2, 4, 2), longArrayOf(3000, 180, 3000),
                ),
            ),
        )

        /**
         * Claude: idle 3 — закрытые глаза (моргание); waving 1–2 рука поднята;
         * jumping 0–4; failed 0–3 плачет, 4 лежит плашмя, 6–7 плачет; waiting 1 и 5
         * прищур, 3 наклон; review 2 и 4 наклон головы.
         */
        private val CLAUDE_CLIPS: Map<PetAction, PetClip> = mapOf(
                PetAction.IDLE to PetClip(
                    0, intArrayOf(0, 1, 3, 2, 0, 4, 5),
                    longArrayOf(2600, 200, 120, 200, 2200, 220, 220),
                ),
                PetAction.HAPPY to PetClip(
                    0, intArrayOf(3, 3, 0, 1, 2, 0, 1, 0),
                    longArrayOf(1800, 1600, 600, 300, 400, 2000, 300, 800),
                    rows = intArrayOf(8, 8, 0, 4, 4, 0, 0, 0),
                ),
                PetAction.WAVE to PetClip(
                    3, intArrayOf(0, 1, 2, 1, 2, 0), longArrayOf(180, 260, 260, 260, 260, 220), loop = false,
                ),
                PetAction.JUMP to PetClip(
                    4, intArrayOf(0, 1, 2, 3, 4), longArrayOf(150, 180, 220, 180, 200), loop = false,
                ),
                PetAction.CELEBRATE to PetClip(
                    4, intArrayOf(0, 1, 2, 3, 4, 2, 1, 0),
                    longArrayOf(140, 170, 210, 170, 200, 210, 170, 260), loop = false,
                ),
                PetAction.SAD to PetClip(
                    5, intArrayOf(0, 1, 1, 0, 6, 7, 7, 6),
                    longArrayOf(900, 1100, 1100, 900, 900, 1100, 1100, 900),
                ),
                PetAction.EXHAUSTED to PetClip(
                    5, intArrayOf(2, 3, 4, 5, 5, 4, 3, 2),
                    longArrayOf(800, 900, 1200, 1400, 1400, 1200, 900, 800),
                ),
                PetAction.SLEEP to PetClip(5, intArrayOf(4, 4), longArrayOf(3000, 3000)),
                PetAction.TIRED to PetClip(
                    6, intArrayOf(1, 0, 1, 1, 5, 0),
                    longArrayOf(1200, 900, 1400, 1400, 1200, 1000), still = 0,
                ),
                PetAction.LOOK_AROUND to PetClip(
                    6, intArrayOf(0, 3, 3, 0, 2, 4, 0),
                    longArrayOf(400, 900, 900, 500, 900, 900, 400), loop = false,
                ),
                PetAction.SIT to PetClip(
                    6, intArrayOf(0, 1, 0), longArrayOf(3000, 180, 3000),
                ),
        )

        val CLAUDE = PetDef(
            id = "claude",
            displayName = "Claude",
            credit = "XiangWang (github.com/xiangking) · MIT",
            accent = 0xFFF2853A,
            clips = CLAUDE_CLIPS,
        )

        /** Питомцы из галерей с раскладкой Claude: те же ряды и кадры, свой атлас и цвет. */
        private fun claudeLike(id: String, displayName: String, credit: String, accent: Long): PetDef {
            // Layout is shared, expressions are not: use each atlas's actual closed eyes/rest pose.
            val blink = when (id) { "drizz" -> 4; "nezukocoder" -> 2; else -> 3 }
            val sleep = if (id == "nezukocoder") 5 else 4
            val idle = PetClip(0, intArrayOf(0, 1, blink, 0, 5, 0),
                longArrayOf(3200, 220, 140, 2800, 220, 1000))
            val restBlink = when (id) { "drizz" -> 3; "aqua-wisp", "nezukocoder" -> 1; else -> 1 }
            return PetDef(id = id, displayName = displayName, credit = credit, accent = accent,
                clips = CLAUDE_CLIPS + mapOf(
                    PetAction.IDLE to idle,
                    PetAction.SLEEP to PetClip(5, intArrayOf(sleep, sleep), longArrayOf(3000, 3000)),
                    PetAction.SIT to PetClip(6, intArrayOf(0, restBlink, 0), longArrayOf(3000, 180, 3000)),
                ))
        }

        val DRIZZ = claudeLike("drizz", "Drizz", "codex-pet.com · автор не указан", 0xFFB4E62E)
        val AQUA_WISP = claudeLike("aqua-wisp", "Aqua Wisp", "codex-pet.com · автор не указан", 0xFF3FD0D8)
        val NEZUKOCODER = claudeLike("nezukocoder", "NezukoCoder", "Miro H. · petdex.dev", 0xFFE0567A)

        val ALL = listOf(CLAUDE, EIGENBLOB, DRIZZ, AQUA_WISP, NEZUKOCODER)

        fun of(id: String): PetDef = ALL.firstOrNull { it.id == id } ?: CLAUDE
    }
}
