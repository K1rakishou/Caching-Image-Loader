import cache.CacheValue
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
import kotlinx.coroutines.io.jvm.javaio.copyTo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import transformers.ImageTransformer
import transformers.FitCenterTransformer
import transformers.ResizeTransformer
import transformers.TransformationType
import java.awt.image.BufferedImage
import java.io.File
import java.lang.RuntimeException
import java.lang.ref.WeakReference
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext


class CachingImageLoader(
  maxDiskCacheSize: Long = defaultDiskCacheSize,
  cacheDir: File = File(System.getProperty("user.dir")),
  private val dispatcher: CoroutineDispatcher = newFixedThreadPoolContext(2, "caching-image-loader"),
  private val showDebugLog: Boolean = true
) : CoroutineScope {
  private val activeRequests = mutableSetOf<String>()
  private val mutex = Mutex()
  private var job = Job()

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

    diskCache = runBlocking {
      return@runBlocking DiskCache(maxDiskCacheSize, imageCacheDir, showDebugLog)
        .apply { init() }
    }

    client = HttpClient(CIO) {
      engine {
        endpoint.apply {
          keepAliveTime = 5000
          connectTimeout = 5000
          connectRetryAttempts = 5
        }
      }
    }
  }

  private fun debugPrint(msg: String) {
    if (showDebugLog) {
      println(msg)
    }
  }

  fun newRequest(): RequestBuilder {
    return RequestBuilder(this)
  }

  private fun runRequest(
    loaderRequest: LoaderRequest,
    saveStrategy: SaveStrategy,
    url: String?,
    transformers: MutableList<ImageTransformer>
  ) {
    if (url == null) {
      throw RuntimeException("Url is not set!")
    }

    launch(dispatcher) {
      if (checkRequestAlreadyInProgress(url)) {
        onError(loaderRequest)
        return@launch
      }

      try {
        val fromCache = diskCache.get(url)
        val transformedBufferedImage = if (fromCache == null) {
          debugPrint("image ($url) does not exist in the cache")

          val downloadResult = downloadImage(url)
          if (downloadResult == null) {
            onError(loaderRequest)
            return@launch
          }

          val (cacheValue, imageType) = downloadResult
          val (transformedImage, appliedTransformations) = applyTransformers(transformers, cacheValue)

          mutex.withLock {
            when (saveStrategy) {
              SaveStrategy.SaveOriginalImage -> diskCache.store(url, cacheValue)
              SaveStrategy.SaveTransformedImage -> {
                val result = ImageIO.write(transformedImage, imageType.value, cacheValue.file)
                if (!result) {
                  throw RuntimeException("Could not save image to disk!")
                }

                diskCache.store(url, CacheValue(cacheValue.file, appliedTransformations.toTypedArray()))
              }
            }
          }

          transformedImage
        } else {
          debugPrint("image ($url) already exists in the cache")

          val (transformedImage, _) = applyTransformers(transformers, fromCache)
          transformedImage
        }

        val image = SwingFXUtils.toFXImage(transformedBufferedImage, null)

        when (loaderRequest) {
          is LoaderRequest.DownloadAsyncRequest -> {
            loaderRequest.channel.send(image)
          }
        }
      } finally {
        activeRequests.remove(url)
      }
    }
  }

  private suspend fun onError(loaderRequest: LoaderRequest) {
    when (loaderRequest) {
      is LoaderRequest.DownloadAsyncRequest -> {
        loaderRequest.channel.send(null)
      }
    }
  }

  private fun applyTransformers(
    transformers: MutableList<ImageTransformer>,
    cacheValue: CacheValue
  ): Pair<BufferedImage, List<TransformationType>> {
    val image = cacheValue.file.inputStream().use { Image(it) }
    val bufferedImage = SwingFXUtils.fromFXImage(image, null)

    var outImage: BufferedImage? = null
    val appliedTransformations = mutableListOf<TransformationType>()

    for (transformer in transformers) {
      val inImage = outImage ?: bufferedImage

      //do not apply a transformation if it has already been applied
      outImage = if (transformer.type in cacheValue.appliedTransformations) {
        inImage
      } else {
        transformer.transform(inImage)
      }

      appliedTransformations += transformer.type
    }

    val resultImage = outImage ?: bufferedImage
    return resultImage to appliedTransformations
  }

  fun shutdown() {
    job.cancel()
    client.close()
  }

  //for tests
  fun shutdownAndClearEverything() {
    shutdown()

    runBlocking {
      diskCache.clear()
    }
  }

  private suspend fun checkRequestAlreadyInProgress(url: String): Boolean {
    return mutex.withLock {
      if (!activeRequests.add(url)) {
        debugPrint("Already in progress")
        return@withLock true
      }

      return@withLock false
    }
  }

  private suspend fun downloadImage(imageUrl: String): Pair<CacheValue, ImageType>? {
    try {
      return client.call(imageUrl).response.use { response ->
        if (response.status != HttpStatusCode.OK) {
          debugPrint("Response status is not OK! (${response.status})")
          return@use null
        }

        val contentTypeString = response.headers.get(HttpHeaders.ContentType)
        if (contentTypeString == null) {
          debugPrint("Content-type is null!")
          return@use null
        }

        val imageType = when (contentTypeString) {
          "image/png" -> ImageType.Png
          "image/jpeg",
          "image/jpg" -> ImageType.Jpg
          else -> null
        }

        if (imageType == null) {
          debugPrint("Content-type is not supported")
          return@use null
        }

        val imageFile = createCachedImageFile(imageUrl)

        imageFile.outputStream().use {
          response.content.copyTo(it)
        }

        return@use CacheValue(imageFile, emptyArray()) to imageType
      }
    } catch (error: Throwable) {
      error.printStackTrace()
      return null
    }
  }

  private fun createCachedImageFile(imageUrl: String): File {
    val fileName = "${System.nanoTime()}_${imageUrl.hashCode().toUInt()}.cached"
    return File(imageCacheDir, fileName)
  }

  private fun setImageError(imageView: WeakReference<ImageView>) {
    //TODO
  }

  private fun setImage(imageView: WeakReference<ImageView>, image: Image) {
    imageView.get()?.let { iv ->
      iv.image = image
    }
  }

  inner class RequestBuilder(
    private val coroutineScope: CoroutineScope
  ) {
    private var url: String? = null
    private var saveStrategy = SaveStrategy.SaveOriginalImage
    private val transformers = mutableListOf<ImageTransformer>()

    fun load(url: String): RequestBuilder {
      this.url = url
      return this
    }

    fun transformers(builder: TransformerBuilder): RequestBuilder {
      transformers.addAll(builder.transformers)
      return this
    }

    fun saveStrategy(saveStrategy: SaveStrategy): RequestBuilder {
      this.saveStrategy = saveStrategy
      return this
    }

    fun getAsync(): Deferred<Image?> {
      return coroutineScope.async { run() }
    }

    fun into(_imageView: ImageView) {
      val imageView = WeakReference(_imageView)

      launch {
        val image = run()
        image?.let { img ->
          setImage(imageView, img)
        }
      }
    }

    private suspend fun run(): Image? {
      val responseChannel = Channel<Image?>(capacity = 1)
      runRequest(LoaderRequest.DownloadAsyncRequest(responseChannel), saveStrategy, url, transformers)
      return responseChannel.receive()
    }
  }

  class TransformerBuilder {
    val transformers = mutableListOf<ImageTransformer>()

    fun fitCenter(width: Int, height: Int): TransformerBuilder {
      if (transformers.any { it is FitCenterTransformer }) {
        throw IllegalStateException("FitCenterTransformer already applied!")
      }

      transformers.add(FitCenterTransformer(width.toDouble(), height.toDouble()) as ImageTransformer)
      return this
    }

    fun fitCenter(view: ImageView): TransformerBuilder {
      if (transformers.any { it is FitCenterTransformer }) {
        throw IllegalStateException("FitCenterTransformer already applied!")
      }

      transformers.add(FitCenterTransformer(view.fitWidth, view.fitHeight) as ImageTransformer)
      return this
    }

    fun resize(newWidth: Int, newHeight: Int): TransformerBuilder {
      if (transformers.any { it is ResizeTransformer }) {
        throw IllegalStateException("ResizeTransformer already applied!")
      }

      transformers.add(ResizeTransformer(newWidth.toDouble(), newHeight.toDouble()) as ImageTransformer)
      return this
    }

    fun resize(newWidth: Double, newHeight: Double): TransformerBuilder {
      if (transformers.any { it is ResizeTransformer }) {
        throw IllegalStateException("ResizeTransformer already applied!")
      }

      transformers.add(ResizeTransformer(newWidth, newHeight) as ImageTransformer)
      return this
    }
  }

  companion object {
    const val defaultDiskCacheSize = 1024 * 1024 * 96L
  }
}