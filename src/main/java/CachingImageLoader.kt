import cache.DiskCache
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import javafx.embed.swing.SwingFXUtils
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.io.jvm.javaio.copyTo
import transformers.FitCenterTransformer
import java.io.File
import java.lang.ref.WeakReference
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext

class CachingImageLoader(
  maxDiskCacheSize: Long = defaultDiskCacheSize,
  cacheDir: File = File(System.getProperty("user.dir")),
  val dispatcher: CoroutineDispatcher = newFixedThreadPoolContext(2, "caching-image-loader")
) : CoroutineScope {
  private val activeRequests = mutableSetOf<String>()

  private var job: Job
  private var requestsActor: SendChannel<Request>

  private var diskCache: DiskCache
  private var imageCacheDir: File
  private var client: HttpClient

  override val coroutineContext: CoroutineContext
    get() = job

  init {
    imageCacheDir = File(cacheDir, "\\image-cache")
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

    requestsActor = actor(job, capacity = 16) {
      for (request in channel) {
        try {
          when (request) {
            is Request.LoadAndShow -> processLoadAndShowRequest(request.url, request.imageView)
            is Request.Load -> {
              val result = processLoadRequest(request.url)
              request.responseChannel.send(result)
            }
          }
        } finally {
          activeRequests.remove(request.url)
        }
      }
    }
  }

  fun shutdown() {
    job.cancel()
    client.close()
  }

  //for tests
  fun shutdownAndClearEverything() {
    shutdown()
    diskCache.clear()
  }

  fun loadImageInto(url: String, imageView: ImageView) {
    if (checkRequestAlreadyInProgress(url)) {
      return
    }

    launch(dispatcher) {
      requestsActor.send(Request.LoadAndShow(url, WeakReference(imageView)))
    }
  }

  fun loadImage(url: String, _callback: (Image) -> Unit) {
    if (checkRequestAlreadyInProgress(url)) {
      return
    }

    val callback = WeakReference(_callback)

    launch(dispatcher) {
      val receiveChannel = Channel<File?>(capacity = 1)
      requestsActor.send(Request.Load(url, receiveChannel))

      val imageFile = receiveChannel.receive()
      if (imageFile == null) {
        return@launch
      }

      val image = imageFile.inputStream().use {
        Image(it)
      }

      callback.get()?.invoke(image)
    }
  }

  fun getImage(url: String): Deferred<Image?> {
    return async(dispatcher) {
      if (checkRequestAlreadyInProgress(url)) {
        return@async null
      }

      val receiveChannel = Channel<File?>(capacity = 1)
      requestsActor.send(Request.Load(url, receiveChannel))

      val imageFile = receiveChannel.receive()
      if (imageFile == null) {
        return@async null
      }

      return@async imageFile.inputStream().use {
        Image(it)
      }
    }
  }

  private fun checkRequestAlreadyInProgress(url: String): Boolean {
    if (!activeRequests.add(url)) {
      println("Already in progress")
      return true
    }

    return false
  }

  private suspend fun processLoadAndShowRequest(imageUrl: String, imageView: WeakReference<ImageView>) {
    try {
      val imageFile = downloadImage(imageUrl)
      if (imageFile != null) {
        setImage(imageView, imageFile)
      } else {
        setImageError(imageView)
      }
    } catch (error: Throwable) {
      error.printStackTrace()
      setImageError(imageView)
    }
  }

  private suspend fun processLoadRequest(imageUrl: String): File? {
    return downloadImage(imageUrl)
  }

  private suspend fun downloadImage(imageUrl: String): File? {
    try {
      if (diskCache.contains(imageUrl)) {
        println("image ($imageUrl) exists in the cache")
        return diskCache.get(imageUrl)
      }

      println("image ($imageUrl) does not exist in the cache")

      return client.call(imageUrl).response.use { response ->
        if (response.status != HttpStatusCode.OK) {
          println("Response status is not OK! (${response.status})")
          return@use null
        }

        val contentTypeString = response.headers.get(HttpHeaders.ContentType)
        if (contentTypeString == null) {
          println("Content-type is null!")
          return@use null
        }

        if (contentTypeString != "image/png" && contentTypeString != "image/jpeg" && contentTypeString != "image/jpg") {
          println("Content-type is not supported")
          return@use null
        }

        val fileName = "${System.nanoTime()}_${imageUrl.hashCode().toUInt()}.cached"
        val imageFile = File(imageCacheDir, fileName)

        imageFile.outputStream().use {
          response.content.copyTo(it)
        }

        diskCache.store(imageUrl, imageFile)
        return@use imageFile
      }
    } catch (error: Throwable) {
      error.printStackTrace()
      return null
    }
  }

  private fun setImageError(imageView: WeakReference<ImageView>) {
    //TODO
  }

  private fun setImage(imageView: WeakReference<ImageView>, file: File) {
    imageView.get()?.let { iv ->
      file.inputStream().use { fileStream ->
        val bufferedImage = ImageIO.read(fileStream)
        val outImage = FitCenterTransformer(iv.fitWidth, iv.fitHeight).transform(bufferedImage)
        iv.image = SwingFXUtils.toFXImage(outImage, null)
      }
    }
  }

  sealed class Request(val url: String) {
    class LoadAndShow(
      url: String,
      val imageView: WeakReference<ImageView>
    ) : Request(url)

    class Load(
      url: String,
      val responseChannel: Channel<File?>
    ) : Request(url)
  }

  companion object {
    const val defaultDiskCacheSize = 1024 * 1024 * 96L
  }
}