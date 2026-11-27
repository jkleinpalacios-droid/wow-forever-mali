package app.gamenative.utils

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import timber.log.Timber

object Net {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val downloadHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    suspend fun fetchFile(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
        expectedSha256: String? = null,
    ) = withContext(Dispatchers.IO) {
        val tmp = File(dest.absolutePath + ".part")
        try {
            downloadHttp.newCall(Request.Builder().url(url).build()).execute().use { rsp ->
                check(rsp.isSuccessful) { "HTTP ${rsp.code}" }
                val body = rsp.body ?: error("empty body")
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                tmp.outputStream().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(8 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
                if (total > 0 && tmp.length() != total) {
                    tmp.delete()
                    error("incomplete download")
                }
                if (expectedSha256 != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        tmp.delete()
                        throw IOException("SHA-256 mismatch for $url (expected $expectedSha256, got $actual); file discarded")
                    }
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    suspend fun fetchFileWithFallback(
        fileName: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val pin = PinnedHashes.forFile(fileName)
        if (pin == null && PinnedHashes.REQUIRE_PIN) {
            throw IOException("Refusing to download $fileName: no SHA-256 pin for it (see PinnedHashes)")
        }
        try {
            fetchFile("https://downloads.gamenative.app/$fileName", dest, onProgress, pin)
        } catch (e: Exception) {
            // A hash mismatch is not a network problem: don't retry it against the mirror.
            if (e.message?.startsWith("SHA-256 mismatch") == true) {
                dest.delete()
                throw e
            }
            Timber.w(e, "Primary download failed; retrying with fallback URL")
            try {
                fetchFile("https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/$fileName", dest, onProgress, pin)
            } catch (e2: Exception) {
                dest.delete()
                throw IOException(
                    "Failed to download $fileName. Please check your network connection or try a VPN.",
                    e2,
                )
            }
        }
    }
}
