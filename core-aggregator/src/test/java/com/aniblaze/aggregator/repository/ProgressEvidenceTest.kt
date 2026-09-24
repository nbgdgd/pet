package com.aniblaze.aggregator.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressEvidenceTest {
    @Test
    fun `opening or half an episode never counts as completed`() {
        val result = progressEvidence(10_000, 0, false, 500_000, 1_000_000, 4_000, ended = false)
        assertFalse(result.completed)
    }

    @Test
    fun `seek straight to end does not fabricate completion`() {
        val result = progressEvidence(20_000, 0, false, 995_000, 1_000_000, 2_000, ended = true)
        assertFalse(result.completed)
    }

    @Test
    fun `verified near-end playback completes an episode`() {
        val result = progressEvidence(950_000, 680_000, false, 980_000, 1_000_000, 30_000, ended = true)
        assertTrue(result.completed)
    }

    @Test
    fun `watching briefly then seeking to end is not completion`() {
        val result = progressEvidence(30_000, 30_000, false, 999_000, 1_000_000, 2_000, ended = true)
        assertFalse(result.completed)
    }

    @Test
    fun `completed mark survives a rewatch checkpoint`() {
        val result = progressEvidence(1_000_000, 30_000, true, 20_000, 1_000_000, 0, ended = false)
        assertTrue(result.completed)
    }
}
