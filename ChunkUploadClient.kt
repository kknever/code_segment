import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

object ChunkUploadClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    // ----------------------------
    // 服务端返回结构
    // ----------------------------
    data class UploadResponse<T>(
        val status: Int,
        val message: String,
        val data: T?,
        val timestamp: String?
    )

    data class UploadFileInfo(
        val url: String,
        val name: String,
        val timestamp: String
    )

    sealed class UploadEvent {

        data class Started(
            val totalBytes: Long,
            val chunkSize: Int,
            val chunksTotal: Int,
            val md5: String,
            val resumed: Boolean,
            val resumedChunksDone: Int
        ) : UploadEvent()

        data class Progress(
            val totalBytes: Long,
            val uploadedBytes: Long,
            val chunksTotal: Int,
            val chunksDone: Int,
            val currentChunkNo: Int,
            val percent: Double
        ) : UploadEvent()

        data class UsedLocalCache(
            val data: UploadFileInfo
        ) : UploadEvent()

        data class Completed(
            val success: Boolean,
            val data: UploadFileInfo? = null,
            val rawResponse: String? = null,
            val error: Throwable? = null,
            val httpCode: Int? = null,
            val responseBodySnippet: String? = null
        ) : UploadEvent()
    }


    fun uploadResumable(
        context: Context,
        uploadUrl: String,
        file: File,
        accessToken: String,
        chunkSize: Int = 4 * 1024 * 1024,
        forceReupload: Boolean = false,
        onEvent: ((UploadEvent) -> Unit)? = null
    ) {
        val stateDir = File(context.cacheDir, "ChunkUploadClient").apply { mkdirs() }
        val key = buildStateKey(file)

        val progressFile = File(stateDir, "$key.upload.progress")
        val resultFile = File(stateDir, "$key.upload.result.json")
        val lockFile = File(stateDir, "$key.upload.lock")

        uploadResumable(
            uploadUrl = uploadUrl,
            file = file,
            accessToken = accessToken,
            chunkSize = chunkSize,
            progressFile = progressFile,
            resultFile = resultFile,
            lockFile = lockFile,
            forceReupload = forceReupload,
            onEvent = onEvent
        )
    }

    private fun buildStateKey(file: File): String {
        val raw = file.absolutePath + "|" + file.name
        return sha1Hex(raw.toByteArray(Charsets.UTF_8)).take(16) + "_" + sanitizeFileName(file.name)
    }

    private fun sanitizeFileName(name: String): String {
        // 用于缓存文件名，去掉不安全字符
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60)
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun uploadResumable(
        uploadUrl: String,
        file: File,
        accessToken: String,
        chunkSize: Int = 4 * 1024 * 1024,
        progressFile: File = File(file.parentFile, "${file.name}.upload.progress"),
        resultFile: File = File(file.parentFile, "${file.name}.upload.result.json"),
        lockFile: File = File(file.parentFile, "${file.name}.upload.lock"),
        forceReupload: Boolean = false,
        onEvent: ((UploadEvent) -> Unit)? = null
    ) {
        require(file.exists() && file.isFile) { "file not exists: ${file.absolutePath}" }
        require(chunkSize > 0)

        withFileLock(lockFile) {
            val length = file.length()
            val chunks = ceil(length.toDouble() / chunkSize.toDouble()).toInt().coerceAtLeast(1)
            val name = file.name
            val md5 = md5Hex(file)

            if (forceReupload) {
                clearLocalState(progressFile = progressFile, resultFile = resultFile)
            } else {
                val cached = loadCachedResultIfMatch(
                    resultFile = resultFile,
                    md5 = md5,
                    length = length,
                    name = name
                )
                if (cached != null) {
                    onEvent?.invoke(UploadEvent.UsedLocalCache(cached))
                    onEvent?.invoke(
                        UploadEvent.Completed(
                            success = true,
                            data = cached,
                            rawResponse = null
                        )
                    )
                    return@withFileLock
                }
            }

            val done = loadDoneSet(progressFile)
            done.removeIf { it < 1 || it > chunks }

            val resumed = (!forceReupload && done.isNotEmpty())
            val resumedChunksDone = done.size

            onEvent?.invoke(
                UploadEvent.Started(
                    totalBytes = length,
                    chunkSize = chunkSize,
                    chunksTotal = chunks,
                    md5 = md5,
                    resumed = resumed,
                    resumedChunksDone = resumedChunksDone
                )
            )

            var uploadedBytes = done.sumOf { chunkNo -> chunkBytes(length, chunkSize, chunkNo) }

            var finalData: UploadFileInfo? = null
            var finalRaw: String? = null

            try {
                RandomAccessFile(file, "r").use { raf ->
                    for (index in 0 until chunks) {
                        val chunkNo = index + 1
                        if (done.contains(chunkNo)) continue

                        val offset = index.toLong() * chunkSize.toLong()
                        val currentSize = min(chunkSize.toLong(), length - offset).toInt().coerceAtLeast(0)

                        val partFile = sliceToTempPart(file, raf, index, offset, currentSize)
                        try {
                            val raw = uploadOneChunk(
                                uploadUrl = uploadUrl,
                                accessToken = accessToken,
                                filePart = partFile,
                                md5 = md5,
                                chunks = chunks,
                                chunk = chunkNo,
                                size = currentSize.toLong(),
                                name = name,
                                length = length
                            )

                            appendDoneAtomic(progressFile, chunkNo)
                            done.add(chunkNo)

                            uploadedBytes += currentSize.toLong()

                            onEvent?.invoke(
                                UploadEvent.Progress(
                                    totalBytes = length,
                                    uploadedBytes = uploadedBytes,
                                    chunksTotal = chunks,
                                    chunksDone = done.size,
                                    currentChunkNo = chunkNo,
                                    percent = if (length == 0L) 100.0 else uploadedBytes.toDouble() * 100.0 / length.toDouble()
                                )
                            )

                            if (chunkNo == chunks) {
                                finalRaw = raw

                                val resp = parseUploadResponse(raw)
                                val ok = (resp.status == 0 || resp.status == 1200)
                                if (!ok) throw RuntimeException("server status=${resp.status}, message=${resp.message}")

                                val bean = resp.data ?: throw RuntimeException("response.data is null, raw=${raw.take(500)}")
                                finalData = bean

                                saveCachedResult(
                                    resultFile = resultFile,
                                    record = CachedResult(
                                        md5 = md5,
                                        length = length,
                                        name = name,
                                        data = bean
                                    )
                                )
                            }
                        } finally {
                            partFile.delete()
                        }
                    }
                }

                if (finalData == null) {
                    val cached = loadCachedResultIfMatch(
                        resultFile = resultFile,
                        md5 = md5,
                        length = length,
                        name = name
                    )

                    if (cached != null) {
                        onEvent?.invoke(UploadEvent.UsedLocalCache(cached))
                        onEvent?.invoke(
                            UploadEvent.Completed(
                                success = true,
                                data = cached,
                                rawResponse = null
                            )
                        )
                        return@withFileLock
                    }

                    throw RuntimeException(
                        "All chunks are done locally but no cached UploadFileInfo found. " +
                            "Set forceReupload=true to upload again."
                    )
                }

                onEvent?.invoke(
                    UploadEvent.Completed(
                        success = true,
                        data = finalData,
                        rawResponse = finalRaw
                    )
                )
            } catch (t: Throwable) {
                val httpCode = (t as? HttpException)?.code
                val snippet = (t as? HttpException)?.body?.take(500)

                onEvent?.invoke(
                    UploadEvent.Completed(
                        success = false,
                        error = t,
                        httpCode = httpCode,
                        responseBodySnippet = snippet
                    )
                )
                throw t
            }
        }
    }

    private data class CachedResult(
        val md5: String,
        val length: Long,
        val name: String,
        val data: UploadFileInfo
    )

    private fun saveCachedResult(resultFile: File, record: CachedResult) {
        resultFile.parentFile?.mkdirs()
        val json = gson.toJson(record)

        val tmp = File(resultFile.parentFile, resultFile.name + ".tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(resultFile)) {
            resultFile.writeText(json, Charsets.UTF_8)
            kotlin.runCatching { tmp.delete() }
        }
    }

    private fun loadCachedResultIfMatch(resultFile: File, md5: String, length: Long, name: String): UploadFileInfo? {
        if (!resultFile.exists()) return null
        return try {
            val record: CachedResult = gson.fromJson(
                resultFile.readText(Charsets.UTF_8),
                TypeToken.get(CachedResult::class.java).type
            )
            if (record.md5 == md5 && record.length == length && record.name == name) record.data else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearLocalState(progressFile: File, resultFile: File) {
        kotlin.runCatching { if (progressFile.exists()) progressFile.delete() }
        kotlin.runCatching { if (resultFile.exists()) resultFile.delete() }
    }

    private fun uploadOneChunk(
        uploadUrl: String,
        accessToken: String,
        filePart: File,
        md5: String,
        chunks: Int,
        chunk: Int,
        size: Long,
        name: String,
        length: Long
    ): String {
        val fileMediaType = "application/octet-stream".toMediaType()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("md5", md5)
            .addFormDataPart("chunks", chunks.toString())
            .addFormDataPart("chunk", chunk.toString())
            .addFormDataPart("size", size.toString())
            .addFormDataPart("name", name)
            .addFormDataPart("length", length.toString())
            .addFormDataPart("file", name, filePart.asRequestBody(fileMediaType))
            .build()

        val req = Request.Builder()
            .url(uploadUrl)
            .addHeader("access_token", accessToken)
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            val respStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} ${resp.message}", respStr)
            return respStr
        }
    }

    private class HttpException(
        val code: Int,
        override val message: String,
        val body: String
    ) : RuntimeException(message)

    private fun parseUploadResponse(raw: String): UploadResponse<UploadFileInfo> {
        val type = TypeToken.getParameterized(
            UploadResponse::class.java,
            UploadFileInfo::class.java
        ).type
        return gson.fromJson(raw, type)
    }

    private fun sliceToTempPart(origin: File, raf: RandomAccessFile, index: Int, offset: Long, size: Int): File {
        raf.seek(offset)
        val tmp = File(origin.parentFile, "${origin.name}.$index.part.tmp")
        tmp.outputStream().use { out ->
            var remaining = size
            val buf = ByteArray(256 * 1024)
            while (remaining > 0) {
                val toRead = min(buf.size, remaining)
                val r = raf.read(buf, 0, toRead)
                if (r < 0) break
                out.write(buf, 0, r)
                remaining -= r
            }
        }
        return tmp
    }

    private fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun loadDoneSet(progressFile: File): MutableSet<Int> {
        if (!progressFile.exists()) return mutableSetOf()
        return progressFile.readLines()
            .mapNotNull { it.trim().toIntOrNull() }
            .toMutableSet()
    }

    private fun appendDoneAtomic(progressFile: File, chunkNo: Int) {
        progressFile.parentFile?.mkdirs()
        RandomAccessFile(progressFile, "rw").use { raf ->
            raf.channel.lock().use {
                raf.seek(raf.length())
                raf.write((chunkNo.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun chunkBytes(totalLen: Long, chunkSize: Int, chunkNo: Int): Long {
        val index = chunkNo - 1
        val offset = index.toLong() * chunkSize.toLong()
        if (offset >= totalLen) return 0L
        return min(chunkSize.toLong(), totalLen - offset)
    }

    private inline fun <T> withFileLock(lockFile: File, block: () -> T): T {
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            val channel: FileChannel = raf.channel
            channel.lock().use { return block() }
        }
    }
}

// test
fun main() {
  ChunkUploadClient.uploadResumable(
        uploadUrl = UPLOAD_URL,
        file = File(xxx),
        "test_token",
        forceReupload = false,
        onEvent = { e ->
            when (e) {
                is ChunkUploadClient.UploadEvent.UsedLocalCache ->
                    println("命中本地缓存：$e")

                is ChunkUploadClient.UploadEvent.Started ->
                    println("开始：md5=${e.md5}, resumed=${e.resumed}, done=${e.resumedChunksDone}/${e.chunksTotal}")

                is ChunkUploadClient.UploadEvent.Progress ->
                    println("上传中：${"%.2f".format(e.percent)}% (${e.chunksDone}/${e.chunksTotal})")

                is ChunkUploadClient.UploadEvent.Completed -> {
                    if (e.success) {
                        println("完成：$e")
                    } else {
                        println("失败：$e")
                    }
                }

                else -> {}
            }
        }
    )
}
