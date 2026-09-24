package com.aniblaze.torrent

import com.aniblaze.aggregator.PersistedTorrent
import com.aniblaze.aggregator.TorrentStreamStage
import com.aniblaze.aggregator.TorrentStreamStatus
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.session_handle
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

internal data class LocalTorrentStream(
    val url: String,
    val fileName: String,
    val fileSize: Long,
)

/** One-process libtorrent owner. Only one cinema torrent is kept alive at a time. */
internal class TorrentStreamEngine(private val root: File) : Closeable {
    private val mutex = Mutex()
    private val manager = SessionManager(false)
    private val telemetry = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "aniblaze-torrent-telemetry").apply { isDaemon = true }
    }
    @Volatile private var started = false
    @Volatile private var current: ActiveTorrent? = null
    @Volatile private var telemetryTask: ScheduledFuture<*>? = null

    /**
     * Last known torrent — live or paused. Survives process restarts via
     * [STATE_FILE_NAME], so Downloads can show and resume it on demand.
     */
    val persisted = MutableStateFlow<PersistedTorrent?>(null)

    init {
        migrateLegacyState()
        cleanupPausedTorrents()
        persisted.value = readStoredRecord()
    }

    suspend fun open(
        magnet: String,
        requestedFileIndex: Int?,
        source: String = "Торрент",
        contentId: String = "",
        quality: String = "",
        infoHash: String = "",
        onStatus: (TorrentStreamStatus) -> Unit = {},
    ): LocalTorrentStream =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // Session FIRST: the metadata fetch below needs it live and
                // fails instantly on an unstarted session.
                ensureStarted()
                root.mkdirs()
                onStatus(
                    TorrentStreamStatus(
                        stage = TorrentStreamStage.METADATA,
                        source = source,
                        contentId = contentId,
                        quality = quality,
                        infoHash = infoHash,
                    ),
                )
                val metadataDir = File(root, "metadata").apply { mkdirs() }
                val info = loadMetadata(magnet, metadataDir)
                check(info.isValid) { "Некорректные метаданные торрента" }
                val files = info.files()
                val fileIndex = selectVideoFile(info, requestedFileIndex)
                val fileSize = files.fileSize(fileIndex)
                check(fileSize > 0L) { "В торренте нет видеофайла" }
                check(root.usableSpace > fileSize + SPACE_RESERVE_BYTES) {
                    "Недостаточно места: для просмотра нужен размер файла и резерв 256 МБ"
                }

                // Single-torrent engine: a new torrent evicts the previous
                // one's data — except a same-release re-open (resume), where
                // downloaded pieces must survive for the libtorrent recheck.
                retireCurrent(deleteFiles = current?.infoHash?.equals(infoHash, ignoreCase = true) != true)

                val priorities = Priority.array(Priority.IGNORE, info.numFiles())
                priorities[fileIndex] = Priority.SEVEN
                manager.download(info, root, null, priorities, null, torrent_flags_t())
                val handle = waitForHandle(info)
                handle.prioritizeFiles(priorities)
                handle.resume()
                prime(handle, info, fileIndex, fileSize)
                try {
                    val fileName = files.fileName(fileIndex)
                    onStatus(snapshot(handle, fileIndex, fileSize, TorrentStreamStage.CONNECTING, fileName, source, contentId, quality, infoHash))
                    check(awaitStartupPiece(handle, info, fileIndex, fileSize, fileName, source, contentId, quality, infoHash, onStatus)) {
                        "Нет доступных пиров или первая часть видео не загрузилась"
                    }
                    val path = File(root, files.filePath(fileIndex)).canonicalFile
                    val safeRoot = root.canonicalFile
                    check(path.path.startsWith(safeRoot.path + File.separator)) {
                        "Небезопасный путь видео в метаданных торрента"
                    }
                    val server = TorrentRangeServer(info, handle, fileIndex, path, fileSize)
                    val port = server.start()
                    val dataDir = safeRoot.toPath().relativize(path.toPath())
                        .firstOrNull()?.toString().orEmpty()
                    val active = ActiveTorrent(
                        handle = handle,
                        server = server,
                        fileIndex = fileIndex,
                        fileSize = fileSize,
                        fileName = path.name,
                        onStatus = onStatus,
                        source = source,
                        infoHash = infoHash,
                        contentId = contentId,
                        quality = quality,
                        magnet = magnet,
                        dataDir = dataDir,
                    )
                    current = active
                    saveRecord(
                        magnet = magnet,
                        fileIndex = fileIndex,
                        source = source,
                        quality = quality,
                        infoHash = infoHash,
                        contentId = contentId,
                        fileName = path.name,
                        dataDir = dataDir,
                        pausedAtMs = 0L,
                    )
                    startTelemetry(active)
                    onStatus(snapshot(handle, fileIndex, fileSize, TorrentStreamStage.STREAMING, path.name, source, contentId, quality, infoHash))
                    Timber.i("[Torrent] streaming %s (%d bytes) on localhost:%d", path.name, fileSize, port)
                    LocalTorrentStream(
                        url = "http://127.0.0.1:$port/video/${fileIndex}",
                        fileName = path.name,
                        fileSize = fileSize,
                    )
                } catch (error: Throwable) {
                    retireCurrent(deleteFiles = true)
                    throw error
                }
            }
        }

    /**
     * Retire the live stream. [keepData] = true closes the local HTTP server
     * and pauses traffic but keeps downloaded pieces + the persisted record,
     * so the page that owns this torrent can resume it later. False deletes
     * everything (failed resolves, explicit "delete").
     */
    fun stop(keepData: Boolean = false) {
        synchronized(this) { retireCurrent(deleteFiles = !keepData) }
    }

    @Synchronized
    fun pause() {
        val active = current ?: return
        active.paused = true
        runCatching { active.handle.pause() }
        markPaused(active)
        active.onStatus(
            snapshot(
                active.handle,
                active.fileIndex,
                active.fileSize,
                TorrentStreamStage.PAUSED,
                active.fileName,
                active.source,
                active.contentId,
                active.quality,
                active.infoHash,
            ),
        )
    }

    @Synchronized
    fun resume() {
        val active = current ?: return
        active.paused = false
        runCatching { active.handle.resume() }
        clearPausedAt()
        active.onStatus(
            snapshot(
                active.handle,
                active.fileIndex,
                active.fileSize,
                TorrentStreamStage.STREAMING,
                active.fileName,
                active.source,
                active.contentId,
                active.quality,
                active.infoHash,
            ),
        )
    }

    private fun ensureStarted() {
        if (started) return
        manager.start()
        started = true
    }

    // ---------- persisted single-torrent record (Downloads + 3-day TTL) ----------

    private fun stateFile(): File = File(root, STATE_FILE_NAME)

    private fun readProps(): java.util.Properties {
        val props = java.util.Properties()
        val file = stateFile()
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
        return props
    }

    private fun writeProps(props: java.util.Properties) {
        root.mkdirs()
        runCatching { stateFile().outputStream().use { props.store(it, null) } }
    }

    private fun saveRecord(
        magnet: String,
        fileIndex: Int,
        source: String,
        quality: String,
        infoHash: String,
        contentId: String,
        fileName: String,
        dataDir: String,
        pausedAtMs: Long,
    ) {
        val props = java.util.Properties()
        props.setProperty(KEY_MAGNET, magnet)
        props.setProperty(KEY_FILE_INDEX, (fileIndex ?: -1).toString())
        props.setProperty(KEY_SOURCE, source)
        props.setProperty(KEY_QUALITY, quality)
        props.setProperty(KEY_INFO_HASH, infoHash)
        props.setProperty(KEY_CONTENT_ID, contentId)
        props.setProperty(KEY_FILE_NAME, fileName)
        props.setProperty(KEY_DATA_DIR, dataDir)
        props.setProperty(KEY_CREATED_AT, System.currentTimeMillis().toString())
        props.setProperty(KEY_PAUSED_AT, pausedAtMs.toString())
        writeProps(props)
        persisted.value = readStoredRecord(props)
        Timber.d("[Torrent] record saved: %s (%s)", fileName, infoHash)
    }

    /** Stamp the pause moment — the 3-day auto-delete countdown starts here. */
    private fun markPaused(active: ActiveTorrent) {
        val props = readProps()
        if (props.getProperty(KEY_INFO_HASH).isNullOrBlank()) {
            saveRecord(
                magnet = active.magnet, fileIndex = active.fileIndex, source = active.source,
                quality = active.quality, infoHash = active.infoHash, contentId = active.contentId,
                fileName = active.fileName, dataDir = active.dataDir,
                pausedAtMs = System.currentTimeMillis(),
            )
            return
        }
        props.setProperty(KEY_PAUSED_AT, System.currentTimeMillis().toString())
        writeProps(props)
        persisted.value = readStoredRecord(props)
    }

    private fun clearPausedAt() {
        val props = readProps()
        if (props.getProperty(KEY_PAUSED_AT) == "0") return
        props.setProperty(KEY_PAUSED_AT, "0")
        writeProps(props)
        persisted.value = readStoredRecord(props)
    }

    private fun clearRecord() {
        runCatching { stateFile().delete() }
        persisted.value = null
    }

    private fun readStoredRecord(props: java.util.Properties = readProps()): PersistedTorrent? {
        val magnet = props.getProperty(KEY_MAGNET).orEmpty()
        val infoHash = props.getProperty(KEY_INFO_HASH).orEmpty()
        if (magnet.isBlank() || infoHash.isBlank()) return null
        return PersistedTorrent(
            magnet = magnet,
            fileIndex = props.getProperty(KEY_FILE_INDEX)?.toIntOrNull()?.takeIf { it >= 0 },
            source = props.getProperty(KEY_SOURCE).orEmpty(),
            quality = props.getProperty(KEY_QUALITY).orEmpty(),
            infoHash = infoHash,
            contentId = props.getProperty(KEY_CONTENT_ID).orEmpty(),
            fileName = props.getProperty(KEY_FILE_NAME).orEmpty(),
            createdAtMs = props.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: 0L,
            pausedAtMs = props.getProperty(KEY_PAUSED_AT)?.toLongOrNull() ?: 0L,
        )
    }

    internal fun storedDataDir(): String = readProps().getProperty(KEY_DATA_DIR).orEmpty()

    /**
     * Auto-delete the known torrent (data + metadata + record) when it has
     * been sitting paused longer than [PersistedTorrent.PAUSED_TTL_MS].
     */
    private fun cleanupPausedTorrents() {
        val props = readProps()
        val pausedAt = props.getProperty(KEY_PAUSED_AT)?.toLongOrNull() ?: 0L
        if (pausedAt <= 0L) return
        if (System.currentTimeMillis() - pausedAt <= PersistedTorrent.PAUSED_TTL_MS) return
        val dataDir = props.getProperty(KEY_DATA_DIR).orEmpty()
        if (dataDir.isNotBlank()) {
            runCatching { File(root, dataDir).deleteRecursively() }
        }
        val infoHash = props.getProperty(KEY_INFO_HASH).orEmpty()
        if (infoHash.isNotBlank()) {
            runCatching { File(File(root, "metadata"), "$infoHash.torrent").delete() }
        }
        clearRecord()
        Timber.i("[Torrent] auto-deleted torrent paused over 3 days")
    }

    /**
     * One-time upgrade from the legacy name→createdAt timestamps: those
     * entries carry no magnet, so they can never be resumed — wipe their data
     * once instead of on every process start as before.
     */
    private fun migrateLegacyState() {
        val legacy = File(root, LEGACY_STATE_FILE_NAME)
        if (!legacy.isFile) return
        root.listFiles()?.forEach { file ->
            if (file.name != STATE_FILE_NAME && file.name != LEGACY_STATE_FILE_NAME) {
                runCatching { file.deleteRecursively() }
            }
        }
        runCatching { legacy.delete() }
        Timber.i("[Torrent] migrated legacy torrent state")
    }

    /**
     * Torrent metadata for [magnet], reusing the saved .torrent file when the
     * same release was opened before — resume then skips the DHT fetch.
     */
    private fun loadMetadata(magnet: String, dir: File): TorrentInfo {
        val btih = Regex("btih:([0-9a-fA-F]{40})").find(magnet)
            ?.groupValues?.get(1)?.lowercase()
        if (btih != null) {
            val saved = File(dir, "$btih.torrent")
            if (saved.isFile) {
                runCatching { TorrentInfo.bdecode(saved.readBytes()) }
                    .getOrNull()?.takeIf { it.isValid }?.let { return it }
            }
        }
        val bytes = manager.fetchMagnet(magnet, METADATA_TIMEOUT_SECONDS, dir)
            ?: error("Торрент не отдал метаданные")
        val info = TorrentInfo.bdecode(bytes)
        if (btih != null) runCatching { File(dir, "$btih.torrent").writeBytes(bytes) }
        return info
    }

    private fun waitForHandle(info: TorrentInfo): TorrentHandle {
        repeat(HANDLE_TIMEOUT_STEPS) {
            val handle = manager.find(info.infoHashV1())
            if (handle != null && handle.isValid) return handle
            if (!sleepOrStop(HANDLE_POLL_MS)) error("Запуск торрента отменён")
        }
        error("Торрент не добавился в локальный движок")
    }

    private fun selectVideoFile(info: TorrentInfo, requested: Int?): Int {
        val files = info.files()
        fun playable(index: Int): Boolean = index in 0 until files.numFiles() &&
            VIDEO_EXTENSIONS.any { files.fileName(index).endsWith(it, ignoreCase = true) }
        if (requested != null && playable(requested)) return requested
        return (0 until files.numFiles())
            .filter(::playable)
            .maxByOrNull(files::fileSize)
            ?: error("В торренте нет поддерживаемого видеофайла")
    }

    private fun prime(handle: TorrentHandle, info: TorrentInfo, fileIndex: Int, fileSize: Long) {
        val first = info.mapFile(fileIndex, 0L, 1).piece()
        val last = info.mapFile(fileIndex, (fileSize - 1L).coerceAtLeast(0L), 1).piece()
        for (piece in first..min(first + 8, last)) runCatching { handle.setPieceDeadline(piece, 0) }
        runCatching { handle.setPieceDeadline(last, 0) }
    }

    private fun awaitStartupPiece(
        handle: TorrentHandle,
        info: TorrentInfo,
        fileIndex: Int,
        fileSize: Long,
        fileName: String,
        source: String,
        contentId: String,
        quality: String,
        infoHash: String,
        onStatus: (TorrentStreamStatus) -> Unit,
    ): Boolean {
        val first = info.mapFile(fileIndex, 0L, 1).piece()
        repeat(STARTUP_WAIT_STEPS) { step ->
            if (!handle.isValid) return false
            if (handle.havePiece(first)) return true
            if (step % STATUS_EVERY_STEPS == 0) {
                onStatus(snapshot(handle, fileIndex, fileSize, TorrentStreamStage.BUFFERING, fileName, source, contentId, quality, infoHash))
            }
            if (!sleepOrStop(STARTUP_POLL_MS)) return false
        }
        return false
    }

    private fun startTelemetry(active: ActiveTorrent) {
        telemetryTask?.cancel(false)
        telemetryTask = telemetry.scheduleAtFixedRate({
            if (current !== active || !active.handle.isValid) return@scheduleAtFixedRate
            runCatching {
                val value = snapshot(
                    active.handle,
                    active.fileIndex,
                    active.fileSize,
                    if (active.paused) TorrentStreamStage.PAUSED else TorrentStreamStage.STREAMING,
                    active.fileName,
                    active.source,
                    active.contentId,
                    active.quality,
                    active.infoHash,
        )
                if (current === active) active.onStatus(value)
            }.onFailure { Timber.d(it, "[Torrent] telemetry unavailable") }
        }, 0L, TELEMETRY_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    private fun snapshot(
        handle: TorrentHandle,
        fileIndex: Int,
        fileSize: Long,
        stage: TorrentStreamStage,
        fileName: String = "",
        source: String,
        contentId: String = "",
        quality: String = "",
        infoHash: String = "",
    ): TorrentStreamStatus {
        val status = handle.status()
        val downloaded = runCatching { handle.fileProgress().getOrNull(fileIndex) }
            .getOrNull() ?: status.totalWantedDone()
        return TorrentStreamStatus(
            stage = stage,
            source = source,
            downloadBytesPerSecond = status.downloadPayloadRate().toLong().coerceAtLeast(0L),
            peers = status.numPeers().coerceAtLeast(0),
            downloadedBytes = downloaded.coerceIn(0L, fileSize),
            totalBytes = fileSize,
            fileName = fileName,
            contentId = contentId,
            quality = quality,
            infoHash = infoHash,
        )
    }

    /**
     * Fully delete the persisted torrent with its files and metadata
     * (explicit user delete or an expired >3-day pause).
     */
    fun deletePersisted() {
        synchronized(this) {
            retireCurrent(deleteFiles = true)
            val dir = storedDataDir()
            if (dir.isNotBlank()) runCatching { File(root, dir).deleteRecursively() }
            persisted.value?.infoHash?.takeIf { it.isNotBlank() }?.let { hash ->
                runCatching { File(File(root, "metadata"), "$hash.torrent").delete() }
            }
            clearRecord()
        }
    }

    @Synchronized
    private fun retireCurrent(deleteFiles: Boolean) {        val old = current ?: return
        current = null
        telemetryTask?.cancel(false)
        telemetryTask = null
        old.server.close()
        runCatching { old.handle.pause() }
        if (started) {
            runCatching {
                if (deleteFiles) manager.remove(old.handle, session_handle.delete_files)
                else manager.remove(old.handle)
            }
        }
        if (deleteFiles) {
            clearRecord()
        } else {
            markPaused(old)
        }
    }

    override fun close() {
        retireCurrent(deleteFiles = true)
        if (started) runCatching { manager.stop() }
        started = false
    }

    private data class ActiveTorrent(
        val handle: TorrentHandle,
        val server: TorrentRangeServer,
        val fileIndex: Int,
        val fileSize: Long,
        val fileName: String,
        val onStatus: (TorrentStreamStatus) -> Unit,
        val source: String,
        val infoHash: String,
        val contentId: String,
        val quality: String,
        val magnet: String = "",
        val dataDir: String = "",
    ) {
        @Volatile var paused: Boolean = false
    }

    private fun sleepOrStop(milliseconds: Long): Boolean = try {
        Thread.sleep(milliseconds)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private companion object {
        const val STATE_FILE_NAME = "torrent_state.properties"
        const val LEGACY_STATE_FILE_NAME = "torrent_timestamps.properties"
        const val KEY_MAGNET = "magnet"
        const val KEY_FILE_INDEX = "fileIndex"
        const val KEY_SOURCE = "source"
        const val KEY_QUALITY = "quality"
        const val KEY_INFO_HASH = "infoHash"
        const val KEY_CONTENT_ID = "contentId"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_DATA_DIR = "dataDir"
        const val KEY_CREATED_AT = "createdAt"
        const val KEY_PAUSED_AT = "pausedAt"
        const val METADATA_TIMEOUT_SECONDS = 40
        const val HANDLE_TIMEOUT_STEPS = 50
        const val HANDLE_POLL_MS = 100L
        const val SPACE_RESERVE_BYTES = 256L * 1024 * 1024
        const val STARTUP_WAIT_STEPS = 250
        const val STARTUP_POLL_MS = 100L
        const val STATUS_EVERY_STEPS = 5
        const val TELEMETRY_PERIOD_MS = 750L
        val VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".avi", ".m4v", ".ts")
    }
}

/**
 * Turns a sparse, still-downloading torrent file into a seekable localhost stream.
 * Every HTTP range request reprioritises pieces around the ExoPlayer playhead.
 */
internal class TorrentRangeServer(
    private val info: TorrentInfo,
    private val handle: TorrentHandle,
    private val fileIndex: Int,
    private val file: File,
    private val fileSize: Long,
) : Closeable {
    private val clients = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "aniblaze-torrent-http").apply { isDaemon = true }
    }
    @Volatile private var running = true
    private lateinit var server: ServerSocket

    fun start(): Int {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        clients.execute {
            while (running) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    clients.execute {
                        try {
                            socket.use(::serve)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } catch (error: java.io.IOException) {
                            // Media3 routinely cancels one Range request and opens another
                            // while probing a container. A closed client is not a server crash.
                            if (running) Timber.d(error, "[Torrent] player replaced HTTP range")
                        } catch (error: Throwable) {
                            if (running) Timber.w(error, "[Torrent] local HTTP client failed")
                        }
                    }
                }.onFailure {
                    runCatching { socket.close() }
                    if (running) Timber.w(it, "[Torrent] could not accept HTTP client")
                }
            }
        }
        return server.localPort
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = CLIENT_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ').uppercase()
        if (method != "GET" && method != "HEAD") return
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
        }
        val range = parseRange(rangeHeader, fileSize)
        val start = range?.first ?: 0L
        val end = range?.last ?: fileSize - 1L
        if (method == "GET" && !waitForPiece(start)) {
            writeError(socket, 503, "Torrent buffer timeout")
            return
        }
        val out = socket.getOutputStream()
        val writer = BufferedWriter(OutputStreamWriter(out, StandardCharsets.ISO_8859_1))
        writer.write(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: ${mime(file.name)}\r\n")
        writer.write("Accept-Ranges: bytes\r\n")
        writer.write("Content-Length: ${end - start + 1}\r\n")
        if (range != null) writer.write("Content-Range: bytes $start-$end/$fileSize\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        if (method == "HEAD") return

        var position = start
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)
            val buffer = ByteArray(HTTP_CHUNK_BYTES)
            while (running && position <= end) {
                if (!waitForPiece(position)) return
                val mapped = info.mapFile(fileIndex, position, 1)
                val inPiece = mapped.start()
                val pieceRemaining = info.pieceLength() - inPiece
                val wanted = min(min(buffer.size.toLong(), end - position + 1), pieceRemaining.toLong()).toInt()
                val read = raf.read(buffer, 0, wanted)
                if (read <= 0) return
                out.write(buffer, 0, read)
                position += read
            }
            out.flush()
        }
    }

    private fun waitForPiece(fileOffset: Long): Boolean {
        val currentPiece = info.mapFile(fileIndex, fileOffset, 1).piece()
        val lastPiece = info.mapFile(fileIndex, fileSize - 1L, 1).piece()
        for (piece in currentPiece..min(currentPiece + LOOKAHEAD_PIECES, lastPiece)) {
            runCatching { handle.setPieceDeadline(piece, (piece - currentPiece) * 350) }
        }
        repeat(PIECE_WAIT_STEPS) {
            if (!running || !handle.isValid) return false
            if (handle.havePiece(currentPiece)) {
                repeat(FILE_WAIT_STEPS) {
                    if (file.exists()) return true
                    if (!sleepOrStop(PIECE_POLL_MS)) return false
                }
                return false
            }
            if (!sleepOrStop(PIECE_POLL_MS)) return false
        }
        return false
    }

    private fun sleepOrStop(milliseconds: Long): Boolean = try {
        Thread.sleep(milliseconds)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun writeError(socket: Socket, code: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().write(
            "HTTP/1.1 $code Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        socket.getOutputStream().write(bytes)
    }

    override fun close() {
        running = false
        if (::server.isInitialized) runCatching { server.close() }
        clients.shutdownNow()
    }

    companion object {
        internal fun parseRange(header: String?, size: Long): LongRange? {
            if (header == null || size <= 0L) return null
            val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(header) ?: return null
            val left = match.groupValues[1].toLongOrNull()
            val right = match.groupValues[2].toLongOrNull()
            val start = when {
                left != null -> left
                right != null -> (size - right).coerceAtLeast(0L)
                else -> return null
            }
            val end = if (left == null) size - 1 else (right ?: size - 1).coerceAtMost(size - 1)
            if (start !in 0 until size || end < start) return null
            return start..end
        }

        private fun mime(name: String): String = when {
            name.endsWith(".mp4", true) || name.endsWith(".m4v", true) -> "video/mp4"
            name.endsWith(".webm", true) -> "video/webm"
            name.endsWith(".ts", true) -> "video/mp2t"
            else -> "video/x-matroska"
        }

        const val CLIENT_TIMEOUT_MS = 65_000
        const val HTTP_CHUNK_BYTES = 256 * 1024
        const val LOOKAHEAD_PIECES = 10
        const val PIECE_WAIT_STEPS = 600
        const val FILE_WAIT_STEPS = 20
        const val PIECE_POLL_MS = 100L
    }
}

