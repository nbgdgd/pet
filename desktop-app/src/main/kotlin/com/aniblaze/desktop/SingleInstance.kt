package com.aniblaze.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * One running AniBlaze at a time. With close-to-tray the process outlives its
 * window, so the desktop shortcut would happily start a SECOND copy (two tray
 * icons, two notification sweeps, two players). A loopback port doubles as the
 * instance lock AND the signal channel: a second launch can't bind, pokes the
 * port instead, and the first instance brings its window back.
 */
object SingleInstance {

    private const val PORT = 48973

    /** Set by Main inside the application scope; runs on the accept thread. */
    @Volatile var onShow: (() -> Unit)? = null

    private var server: ServerSocket? = null

    /** True = we are the first instance (lock held). False = another instance is
     *  already running and has been signalled to show its window — just exit. */
    fun acquireOrSignal(): Boolean = try {
        val socket = ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())
        server = socket
        Thread {
            while (!socket.isClosed) {
                runCatching {
                    socket.accept().close() // any connection = "покажись"
                    com.aniblaze.desktop.player.PlayerDiagnostics.log("singleInstance.showRequest")
                    onShow?.invoke()
                }
            }
        }.apply { isDaemon = true; name = "aniblaze-single-instance" }.start()
        true
    } catch (_: Exception) {
        runCatching { Socket(InetAddress.getLoopbackAddress(), PORT).close() }
        false
    }
}
