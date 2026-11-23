package com.moviebox.android.data

import android.content.Context
import com.moviebox.android.model.DownloadStatus
import com.moviebox.android.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class RangeDownloadManager(private val context: Context) {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    fun enqueue(title: String, url: String, headers: Map<String, String> = emptyMap()) {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(id = id, title = title, url = url, headers = headers, status = DownloadStatus.QUEUED)
        _tasks.update { it + task }
        startOrResume(id)
    }

    fun pause(id: String) {
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        updateTask(id) { it.copy(status = DownloadStatus.PAUSED) }
    }

    fun startOrResume(id: String) {
        val task = _tasks.value.find { it.id == id } ?: return
        if (activeJobs[id]?.isActive == true) return

        val safeName = task.title.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".mp4"
        val outFile = File(context.getExternalFilesDir("downloads"), safeName)
        outFile.parentFile?.mkdirs()

        activeJobs[id] = scope.launch {
            try {
                val existingBytes = if (outFile.exists()) outFile.length() else 0L
                updateTask(id) { it.copy(status = DownloadStatus.DOWNLOADING, downloadedBytes = existingBytes) }

                val reqBuilder = Request.Builder().url(task.url)
                task.headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
                if (existingBytes > 0L) {
                    reqBuilder.addHeader("Range", "bytes=$existingBytes-")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        error("HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty body")
                    val contentLength = body.contentLength()
                    val total = if (contentLength > 0) existingBytes + contentLength else 0L
                    val append = response.code == 206 && existingBytes > 0L

                    body.byteStream().use { input ->
                        FileOutputStream(outFile, append).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var downloaded = if (append) existingBytes else 0L
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                updateTask(id) {
                                    it.copy(downloadedBytes = downloaded, totalBytes = total, progress = progress)
                                }
                            }
                        }
                    }
                }
                updateTask(id) { it.copy(status = DownloadStatus.COMPLETED, progress = 1f) }
            } catch (_: Exception) {
                val currentStatus = _tasks.value.find { it.id == id }?.status
                if (currentStatus != DownloadStatus.PAUSED) {
                    updateTask(id) { it.copy(status = DownloadStatus.FAILED) }
                }
            }
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
