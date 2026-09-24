package com.aniblaze.aggregator.lampa

import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LampaSandboxTest {
    @Test
    fun `плагин не видит Java и reflection`() {
        val runtime = LampaRuntime(OkHttpClient())
        try {
            val error = runtime.loadPlugin(
                """
                if (typeof Packages !== 'undefined') throw 'Packages exposed';
                if (typeof java !== 'undefined') throw 'java exposed';
                if (__bridge.getClass) throw 'bridge reflection exposed';
                Lampa.Component.add('safe', function(){});
                """.trimIndent(),
            )
            assertNull(error)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `локальные и не https адреса запрещены`() {
        assertFailsWith<SecurityException> { LampaNetworkPolicy.validateHttps("http://example.com/plugin.js") }
        assertFailsWith<SecurityException> { LampaNetworkPolicy.validateHttps("https://localhost/plugin.js") }
        assertFailsWith<SecurityException> { LampaNetworkPolicy.validateHttps("file:///tmp/plugin.js") }
    }
}
