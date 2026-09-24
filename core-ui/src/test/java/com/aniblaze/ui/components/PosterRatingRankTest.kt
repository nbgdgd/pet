package com.aniblaze.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterRatingRankTest {
    @Test fun mapsFivePointScale() {
        assertEquals("Легенда", posterRatingRank(4.9, 5.0)?.label)
        assertEquals("Прекрасно", posterRatingRank(4.0, 5.0)?.label)
        assertEquals("Нормально", posterRatingRank(3.5, 5.0)?.label)
        assertEquals("Ужас", posterRatingRank(2.0, 5.0)?.label)
    }

    @Test fun mapsTenPointScaleWithoutTreatingItAsFive() {
        assertEquals("Легенда", posterRatingRank(8.7, 10.0)?.label)
        assertEquals("Прекрасно", posterRatingRank(8.0, 10.0)?.label)
        assertEquals("Нормально", posterRatingRank(7.0, 10.0)?.label)
        assertEquals("Ужас", posterRatingRank(6.0, 10.0)?.label)
    }

    @Test fun hidesUnknownRating() {
        assertNull(posterRatingRank(0.0, 10.0))
        assertNull(posterRatingRank(Double.NaN, 10.0))
    }
}
