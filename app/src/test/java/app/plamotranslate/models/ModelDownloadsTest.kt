package app.plamotranslate.models

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * モデルのダウンロードを、PCの上に立てた小さなHTTPサーバーで確かめる。
 * 続きからの取得（Range）、相対パスのリダイレクト（Hugging Faceの小さいファイルがこの形）、ハッシュの照合を見る。
 * Androidの単体テストではJDKのHTTPサーバーが使えないので、ソケットで最小限の応答を返す。
 */
class ModelDownloadsTest {
    private val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    @Volatile private var rangeRequests = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("plamo-dl").toFile()
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    var range: Int? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            range = line.substringAfter("bytes=").substringBefore("-").trim().toInt()
                        }
                    }
                    val out = s.getOutputStream()
                    when {
                        path == "/redirect" -> out.write("HTTP/1.1 307 Temporary Redirect\r\nLocation: /file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        range != null -> {
                            rangeRequests++
                            val from = range
                            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: ${body.size - from}\r\n" +
                                "Content-Range: bytes $from-${body.size - 1}/${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            out.write(body, from, body.size - from)
                        }
                        else -> {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                    }
                    out.flush()
                    // 読み残しがあるまま閉じると相手に接続のリセットが届くので、送り終えたことを伝えてから読み切る
                    s.shutdownOutput()
                    s.soTimeout = 2_000
                    runCatching { while (reader.read() >= 0) Unit }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun model(path: String, hash: String = sha) = ModelFile(
        label = "試験",
        url = "http://127.0.0.1:${server.localPort}$path",
        path = "x/test.bin",
        size = body.size.toLong(),
        sha256 = hash,
    )

    @Test
    fun 最後まで落として置き場へ移す() = runBlocking {
        val target = File(dir, "x/test.bin")
        ModelDownloads.download(model("/file"), target) {}
        assertArrayEquals(body, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun 途中のファイルがあれば続きから取る() = runBlocking {
        val target = File(dir, "x/test.bin")
        target.parentFile!!.mkdirs()
        File(target.path + ".part").writeBytes(body.copyOf(100_000))
        ModelDownloads.download(model("/file"), target) {}
        assertArrayEquals(body, target.readBytes())
        assertEquals(1, rangeRequests)
    }

    @Test
    fun 途中のファイルが別の版なら最初から取り直す() = runBlocking {
        val target = File(dir, "x/test.bin")
        target.parentFile!!.mkdirs()
        File(target.path + ".part").writeBytes(ByteArray(100_000) { 7 })
        ModelDownloads.download(model("/file"), target) {}
        assertArrayEquals(body, target.readBytes())
        assertEquals(1, rangeRequests)
    }

    @Test
    fun 相対パスのリダイレクトに付いていく() = runBlocking {
        val target = File(dir, "x/test.bin")
        ModelDownloads.download(model("/redirect"), target) {}
        assertArrayEquals(body, target.readBytes())
    }

    @Test
    fun ハッシュが合わなければ使わず途中のファイルも消す() = runBlocking {
        val target = File(dir, "x/test.bin")
        val failed = runCatching { ModelDownloads.download(model("/file", hash = "0".repeat(64)), target) {} }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }
}
