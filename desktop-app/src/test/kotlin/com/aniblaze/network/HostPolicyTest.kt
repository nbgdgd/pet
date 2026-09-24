package com.aniblaze.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Политика хостов молчит, пока приложение её не включило; правила — по хосту и поддоменам. */
class HostPolicyTest {
    @Test fun `выключена по умолчанию - правил нет, включена - shikimori и jikan под правилом`() {
        assertNull(HostPolicy.ruleFor("https://shikimori.one/api/animes/1"))
        HostPolicy.enabled = true
        try {
            assertNotNull(HostPolicy.ruleFor("https://shikimori.one/api/animes/1"))
            assertNotNull(HostPolicy.ruleFor("https://api.jikan.moe/v4/anime/1"))
            assertEquals(350L, HostPolicy.ruleFor("https://shikimori.one/api/animes/1")!!.minIntervalMs)
            assertNull(HostPolicy.ruleFor("https://api.yummyani.me/v2/anime"))
            assertNull(HostPolicy.ruleFor("not a url"))
        } finally {
            HostPolicy.enabled = false
        }
    }
}
