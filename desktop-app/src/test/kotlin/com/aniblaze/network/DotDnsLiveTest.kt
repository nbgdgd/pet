package com.aniblaze.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * LIVE-проверка шифрованного резолвера.
 *
 * Повод: в журнале приложения на КАЖДЫЙ запрос стояло «DoT certificate hostname
 * mismatch for dns.mullvad.net», то есть шифрованный DNS не отработал ни разу за всё
 * время — и всё уходило в системный резолвер, ровно тот, ради обхода которого DoT и
 * заводили. При этом сам сертификат сервера в полном порядке: снят 19.08.2026,
 * subject `CN=gb-lon-dns-301.mullvad.net`, в SAN есть `dns.mullvad.net`, срок до
 * 28.09.2026. Значит, врала проверка, а не сервер.
 */
class DotDnsLiveTest {

    private val serverHost = "dns.mullvad.net"
    private val serverIp = "194.242.2.2"

    private fun connect(identify: Boolean): SSLSocket {
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        socket.connect(InetSocketAddress(serverIp, 853), 5000)
        socket.soTimeout = 5000
        socket.sslParameters = socket.sslParameters.apply {
            serverNames = listOf(SNIHostName(serverHost))
            if (identify) endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
        return socket
    }

    @Test
    fun `дефолтный верификатор HttpsURLConnection всегда говорит нет`() {
        // Вот и вся поломка. `HttpsURLConnection.getDefaultHostnameVerifier()` в JDK
        // возвращает НЕ проверяльщик имени, а заглушку, которая отвечает false всегда:
        // настоящее сличение имени делает сам движок TLS, а верификатор спрашивают
        // лишь как последний шанс переопределить УЖЕ провалившуюся проверку. Ставить
        // на нём `check(ok)` — значит гарантированно упасть на исправном сервере.
        connect(identify = false).use { socket ->
            val verdict = HttpsURLConnection.getDefaultHostnameVerifier().verify(serverHost, socket.session)
            assertFalse(
                "если верификатор вдруг начал отвечать true — старый код был прав, а этот тест устарел",
                verdict,
            )
        }
    }

    @Test
    fun `сличение имени движком TLS проходит`() {
        // Правильный способ: попросить сам TLS сверить имя (endpointIdentification).
        // Тогда несовпадение — это исключение на рукопожатии, а успех — просто
        // успешное соединение, и никаких ручных проверок не нужно.
        connect(identify = true).use { socket ->
            assertTrue("рукопожатие с проверкой имени должно быть валидным", socket.session.isValid)
        }
    }

    @Test
    fun `резолвер отдаёт адреса и ставит IPv4 первым`() {
        val addresses = DotDns(serverHost, serverIp).lookup("aniliberty.top")
        assertTrue("шифрованный резолвер не ответил ни одним адресом", addresses.isNotEmpty())
        assertTrue(
            "первым обязан идти IPv4: у части хостов AAAA есть, а по нему никто не отвечает",
            addresses.first() is Inet4Address,
        )
    }
}
