package http

import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.client.response.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import kotlinx.coroutines.*
import kotlinx.coroutines.io.readFully
import java.io.File
import java.util.concurrent.CompletableFuture

class DefaultHttpClient(
  private val projectDirectory: File,
  private val showDebugLog: Boolean,
  private val dispatcher: CoroutineDispatcher = newFixedThreadPoolContext(2, "downloader")
) : HttpClientFacade, CoroutineScope {
  private val job = Job()
  private val chunkSize = 4096L
  private val tempFilesDir = File(projectDirectory, "\\temp-files")

  override val coroutineContext = job

  private val client = HttpClient(CIO) {
    engine {
      endpoint.apply {
        keepAliveTime = 5000
        connectTimeout = 5000
        connectRetryAttempts = 5
      }
    }
  }

  init {
    if (!tempFilesDir.exists()) {
      if (!tempFilesDir.mkdirs()) {
        throw IllegalStateException("Could not create temp files directory: ${tempFilesDir.absolutePath}")
      }
    }
  }

  override fun close() {
    job.cancel()
    client.close()
  }

  override fun fetchImage(url: String): CompletableFuture<ResponseData?> {
    val future = CompletableFuture<ResponseData?>()

    launch(dispatcher) {
      try {
        client.call(url).response.use { response ->
          if (response.status != HttpStatusCode.OK) {
            debugPrint("Response status is not OK! (${response.status})")
            future.complete(null)
            return@launch
          }

          val contentTypeString = response.headers[HttpHeaders.ContentType]
          if (contentTypeString == null) {
            debugPrint("Content-type is null!")
            future.complete(null)
            return@launch
          }

          val contentLength = response.contentLength()
          if (contentLength == null) {
            debugPrint("Content length is null!")
            future.complete(null)
            return@launch
          }

          val contentFile = createTempFile(url)
          writeResponseToFile(contentFile, contentLength, response)

          future.complete(ResponseData(contentTypeString, contentFile))
        }
      } catch (error: Throwable) {
        error.printStackTrace()
        future.complete(null)
      }
    }

    return future
  }

  private fun createTempFile(imageUrl: String): File {
    val fileName = "${System.nanoTime()}_${imageUrl.hashCode().toUInt()}.cached"
    return File(tempFilesDir, fileName)
  }

  private suspend fun writeResponseToFile(
    contentFile: File,
    contentLength: Long,
    response: HttpResponse
  ) {
    contentFile.outputStream().use { stream ->
      for (offset in 0 until contentLength step chunkSize) {
        val chunk = if (contentLength - offset > chunkSize) {
          chunkSize
        } else {
          contentLength - offset
        }

        val array = ByteArray(chunk.toInt())
        response.content.readFully(array)

        stream.write(array)
      }
    }
  }

  private fun debugPrint(msg: String) {
    if (showDebugLog) {
      println(msg)
    }
  }
}