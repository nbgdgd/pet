package com.aniblaze.network

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS-over-TLS (RFC 7858) resolver exposed as an OkHttp [Dns].
 *
 * Connects to [serverIp]:[port] over TLS (SNI/cert host = [serverHost]) and runs
 * each query on that channel. We need DoT specifically — not just DoH — because on
 * some RF networks that poison plain DNS, Mullvad is reachable over DoT (port 853,
 * what Android's "Private DNS" uses) while DoH (port 443) is blocked. That exact
 * asymmetry was observed on a real blocking network.
 *
 * Bootstrapped with a literal [serverIp] so it never needs the system resolver to
 * find its own server.
 */
class DotDns(
    private val serverHost: String,
    private val serverIp: String,
    private val port: Int = 853,
    private val timeoutMs: Int = 3000,
) : Dns {

    /**
     * IPv4 спрашивается первым, а IPv6 — только если четвёртого нет вовсе.
     *
     * Не вкусовщина: у части хостов AAAA есть, а по нему НИКТО НЕ ОТВЕЧАЕТ. Замерено
     * 19.08.2026 — `api.anixart.tv` и раздающие поток `p12/p14.solodcdn.com` отдают
     * AAAA, соединение по которому отлетает мгновенно, тогда как по IPv4 те же хосты
     * отвечают за доли секунды. Отдай мы адреса в обратном порядке — каждый запрос
     * начинался бы с ожидания таймаута.
     */
    override fun lookup(hostname: String): List<InetAddress> {
        val a = query(hostname, TYPE_A)
        return a.ifEmpty { query(hostname, TYPE_AAAA) }
    }

    private fun query(hostname: String, type: Int): List<InetAddress> {
        openTls().use { socket ->
            val packet = buildQuery(hostname, type)
            DataOutputStream(socket.outputStream).apply {
                writeShort(packet.size) // DNS-over-TCP/TLS: 2-byte length prefix
                write(packet)
                flush()
            }
            val input = DataInputStream(socket.inputStream)
            val len = input.readUnsignedShort()
            val response = ByteArray(len)
            input.readFully(response)
            return parseAddresses(response, type)
        }
    }

    private fun openTls(): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(serverIp, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.sslParameters = socket.sslParameters.apply {
            serverNames = listOf(SNIHostName(serverHost))
            // Имя сервера сверяет САМ движок TLS — несовпадение станет исключением на
            // рукопожатии.
            //
            // Здесь стояла проверка через `HttpsURLConnection.getDefaultHostnameVerifier()`,
            // и она валила КАЖДЫЙ запрос: этот метод возвращает не проверяльщик имени,
            // а заглушку, которая отвечает false всегда — настоящее сличение делает
            // TLS, а верификатор спрашивают лишь как последний шанс переопределить уже
            // провалившуюся проверку. Из-за этого шифрованный DNS не отработал ни разу
            // за всё время: в журнале на каждый запрос стояло «DoT certificate hostname
            // mismatch for dns.mullvad.net», и всё уходило в системный резолвер — ровно
            // тот, ради обхода которого DoT и заводили. Сертификат при этом исправен
            // (проверено 19.08.2026: SAN содержит dns.mullvad.net, срок до 28.09.2026).
            endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
        return socket
    }

    private fun buildQuery(hostname: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: id=0x1234, flags=RD, QDCOUNT=1, AN/NS/AR=0
        out.write(intArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in hostname.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)            // end of QNAME
        out.write(0x00); out.write(type)  // QTYPE
        out.write(0x00); out.write(0x01)  // QCLASS = IN
        return out.toByteArray()
    }

    private fun parseAddresses(msg: ByteArray, type: Int): List<InetAddress> {
        if (msg.size < 12) return emptyList()
        val anCount = u16(msg, 6)
        var pos = skipName(msg, 12) + 4 // question name + QTYPE + QCLASS
        val out = mutableListOf<InetAddress>()
        repeat(anCount) {
            if (pos + 10 > msg.size) return out
            pos = skipName(msg, pos)
            if (pos + 10 > msg.size) return out
            val rrType = u16(msg, pos)
            val rdLength = u16(msg, pos + 8)
            val rdStart = pos + 10
            if (rdStart + rdLength > msg.size) return out
            if (rrType == type && (rdLength == 4 || rdLength == 16)) {
                out += InetAddress.getByAddress(msg.copyOfRange(rdStart, rdStart + rdLength))
            }
            pos = rdStart + rdLength
        }
        return out
    }

    /** Offset just past the name at [start], honouring 0xC0 compression pointers. */
    private fun skipName(msg: ByteArray, start: Int): Int {
        var pos = start
        while (pos < msg.size) {
            val len = msg[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return pos + 2
                else -> pos += len + 1
            }
        }
        return pos
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun ByteArrayOutputStream.write(values: IntArray) = values.forEach { write(it) }

    private companion object {
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
    }
}
