package com.apsu.gamestream.update

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val htmlUrl: String,
    val name: String,
)

class ReleaseUpdateChecker(
    private val apiUrl: String = LATEST_RELEASE_API_URL,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    fun check(currentVersionName: String, callback: (Result<ReleaseInfo?>) -> Unit): AutoCloseable {
        val mainHandler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "solaris-release-check").apply { isDaemon = true }
        }
        lateinit var future: Future<*>

        fun finish(result: Result<ReleaseInfo?>) {
            if (!completed.compareAndSet(false, true)) return
            mainHandler.removeCallbacksAndMessages(completed)
            mainHandler.post { callback(result) }
            executor.shutdownNow()
        }

        val timeoutRunnable = Runnable {
            if (!completed.compareAndSet(false, true)) return@Runnable
            future.cancel(true)
            executor.shutdownNow()
            callback(Result.failure(TimeoutException("Release check timed out")))
        }

        future = executor.submit {
            try {
                val latest = fetchLatestRelease()
                val update = latest?.takeIf {
                    ReleaseVersions.isNewer(latestVersion = it.versionName, currentVersion = currentVersionName)
                }
                finish(Result.success(update))
            } catch (ignored: CancellationException) {
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (throwable: Throwable) {
                finish(Result.failure(throwable))
            }
        }

        mainHandler.postDelayed(timeoutRunnable, completed, timeoutMillis.coerceAtMost(DEFAULT_TIMEOUT_MILLIS))
        return AutoCloseable {
            if (!completed.compareAndSet(false, true)) return@AutoCloseable
            mainHandler.removeCallbacks(timeoutRunnable)
            future.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMillis.coerceAtMost(DEFAULT_TIMEOUT_MILLIS).toInt()
            readTimeout = timeoutMillis.coerceAtMost(DEFAULT_TIMEOUT_MILLIS).toInt()
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Solaris-Android")
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in HTTP_SUCCESS_RANGE) {
                null
            } else {
                ReleaseVersions.parseReleaseInfo(readLimited(connection.inputStream, MAX_RESPONSE_BYTES))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(inputStream: InputStream, maxBytes: Int): String =
        inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.toString(Charsets.UTF_8.name())
        }

    companion object {
        private const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/vairacing-tech/Solaris/releases/latest"
        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}

object ReleaseVersions {
    fun isNewer(latestVersion: String, currentVersion: String): Boolean {
        val latest = numericParts(latestVersion)
        val current = numericParts(currentVersion)
        if (latest.isEmpty() || current.isEmpty()) return false
        val count = maxOf(latest.size, current.size)
        for (index in 0 until count) {
            val latestPart = latest.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (latestPart != currentPart) {
                return latestPart > currentPart
            }
        }
        return false
    }

    fun parseReleaseInfo(json: String): ReleaseInfo? {
        val tagName = jsonStringField(json, "tag_name") ?: return null
        val htmlUrl = jsonStringField(json, "html_url") ?: return null
        return ReleaseInfo(
            tagName = tagName,
            versionName = tagName.removePrefix("v").removePrefix("V"),
            htmlUrl = htmlUrl,
            name = jsonStringField(json, "name") ?: tagName,
        )
    }

    private fun numericParts(version: String): List<Int> =
        Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun jsonStringField(json: String, fieldName: String): String? {
        val pattern = Regex("\"${Regex.escape(fieldName)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return pattern.find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }
}
