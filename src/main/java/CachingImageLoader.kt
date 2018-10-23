import cache.DiskCache
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.io.jvm.javaio.copyTo
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext

class CachingImageLoader : CoroutineScope {
  private val defaultDiskCacheSize = 1024 * 1024 * 96L
  private val defaultMemoryCacheSize = 1024 * 1024 * 32L

  private lateinit var job: Job
  private lateinit var requestsActor: SendChannel<Request>

  private lateinit var diskCache: DiskCache
  private lateinit var imageCacheDir: File
  private lateinit var client: HttpClient

  override val coroutineContext: CoroutineContext
    get() = job

  fun init(
    maxDiskCacheSize: Long = defaultDiskCacheSize,
    maxMemoryCacheSize: Long = defaultMemoryCacheSize
  ) {
    imageCacheDir = File(System.getProperty("user.dir") + "\\image-cache")
    if (!imageCacheDir.exists()) {
      if (!imageCacheDir.mkdirs()) {
        throw IllegalStateException("Could not create image cache directory: ${imageCacheDir.absolutePath}")
      }
    }

    diskCache = DiskCache(maxDiskCacheSize, imageCacheDir).apply { init() }

    client = HttpClient(CIO) {
      engine {
        endpoint.apply {
          keepAliveTime = 5000
          connectTimeout = 5000
          connectRetryAttempts = 5
        }
      }
    }

    job = Job()

    requestsActor = actor(job, capacity = 4) {
      for (request in channel) {
        processRequest(request.url, request.imageView)
      }
    }
  }

  fun destroy() {
    job.cancel()
  }

  suspend fun loadImageInto(url: String, imageView: ImageView) {
    val imgView = WeakReference(imageView)

    if (diskCache.contains(url)) {
      diskCache.get(url)?.let { file ->
        println("FROM CACHE")
        setImage(imgView, file)
      } ?: kotlin.run { println("NOTHING!") }

      return
    }

    println("FROM NETWORK")
    requestsActor.send(Request(url, imgView))
  }

  private suspend fun processRequest(imageUrl: String, imageView: WeakReference<ImageView>) {
    client.call(imageUrl).response.use { response ->
      if (response.status != HttpStatusCode.OK) {
        imageView.get()?.let { iv ->
          //TODO: set error image
        }

        return@use
      }

      //TODO: change name  generation to something else
      val file = File(imageCacheDir, System.currentTimeMillis().toString())

      //TODO: ensure the stream is closed
      file.outputStream().use {
        response.content.copyTo(it)
      }

      diskCache.store(imageUrl, file)
      setImage(imageView, file)
    }
  }

  private fun setImage(imageView: WeakReference<ImageView>, file: File) {
    imageView.get()?.let { iv ->
      iv.image = Image(file.inputStream())
    }
  }

  class Request(
    val url: String,
    val imageView: WeakReference<ImageView>
  )
}