package http

import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.io.jvm.javaio.toInputStream
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

class DefaultHttpClient(
  private val showDebugLog: Boolean
) : HttpClientFacade, CoroutineScope {
  private val job = Job()
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

  override fun close() {
    job.cancel()
    client.close()
  }

  override fun fetchImage(url: String): CompletableFuture<ResponseData?> {
    val future = CompletableFuture<ResponseData?>()

    launch(Dispatchers.IO) {
      try {
        client.call(url).response.use { response ->
          val contentTypeString = response.headers.get(HttpHeaders.ContentType)
          if (contentTypeString == null) {
            debugPrint("Content-type is null!")
            future.complete(null)
            return@launch
          }

          future.complete(ResponseData(response.status.value, contentTypeString, response.content.toInputStream()))
        }
      } catch (error: Throwable) {
        future.complete(null)
      }
    }

    return future
  }

  private fun debugPrint(msg: String) {
    if (showDebugLog) {
      println(msg)
    }
  }
}