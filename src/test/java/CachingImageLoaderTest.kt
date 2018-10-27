import builders.TransformerBuilder
import cache.CacheInfoRecord
import core.CachingImageLoader
import core.SaveStrategy
import http.HttpClientFacade
import http.ResponseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import transformations.TransformationType
import java.io.File
import java.lang.RuntimeException
import java.util.concurrent.CompletableFuture
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachingImageLoaderTest {
  private lateinit var defaultImageLoader: CachingImageLoader
  private lateinit var imageLoaderWithSmallCache: CachingImageLoader

  private val cacheDir = File(System.getProperty("user.dir"), "\\image-cache")
  private val cacheInfoFile = File(cacheDir, "disk-cache.dat")

  private val imageUrls = listOf(
    "https://i.imgur.com/1.jpg",
    "https://i.imgur.com/2.jpg",
    "https://i.imgur.com/3.jpg",
    "https://i.imgur.com/4.jpg",
    "https://i.imgur.com/5.png",
    "https://i.imgur.com/6.jpg",
    "https://i.imgur.com/7.png"
  )

  private fun listFiles(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name }
  }

  private fun readCacheInfoFile(): List<CacheInfoRecord> {
    val lines = cacheInfoFile.readLines()
    val cacheEntries = mutableListOf<CacheInfoRecord>()

    for (line in lines) {
      val split = line.split(";")
      val (url, filePath, addedOnStr, appliedTransformationsStr) = split
      assertTrue(url.isNotEmpty())

      val file = File(filePath)
      assertTrue(file.exists())
      assertTrue(file.isFile)

      val addedOn = addedOnStr.toLong()

      val appliedTransformations = appliedTransformationsStr
        .removePrefix("(")
        .removeSuffix(")")

      val transformations = when {
        appliedTransformations.isEmpty() -> emptyArray()
        else -> {
          appliedTransformations
            .split(",")
            .map { it.toInt() }
            .map { TransformationType.fromInt(it) }
            .mapNotNull { it }
            .toTypedArray()
        }
      }

      cacheEntries += CacheInfoRecord(url, file, addedOn, transformations)
    }

    return cacheEntries
  }

  @Before
  fun init() {
    if (::defaultImageLoader.isInitialized) {
      defaultImageLoader.clearCache()
    }

    val fakeHttpClient = Mockito.mock(HttpClientFacade::class.java)

    Mockito.doAnswer { invocationOnMock ->
      val url = invocationOnMock.getArgument(0) as String
      val fileName = url.substring(20)
      val file = File(System.getProperty("user.dir"), "\\src\\test\\resources\\$fileName")

      val contentType = when {
        fileName.endsWith("png") -> "image/png"
        fileName.endsWith("jpg") -> "image/jpg"
        else -> throw RuntimeException("Unknown extension")
      }

      val future = CompletableFuture<ResponseData?>()
      future.complete(ResponseData(200, contentType, file.inputStream()))

      return@doAnswer future

    }.`when`(fakeHttpClient).fetchImage(Mockito.anyString())

    defaultImageLoader = CachingImageLoader(
      client = fakeHttpClient,
      dispatcher = Dispatchers.Unconfined,
      showDebugLog = true
    )

    imageLoaderWithSmallCache = CachingImageLoader(
      client = fakeHttpClient,
      dispatcher = Dispatchers.Unconfined,
      maxDiskCacheSize = 128 * 1024,
      showDebugLog = true
    )
  }

  @After
  fun tearDown() {
    if (::defaultImageLoader.isInitialized) {
      defaultImageLoader.shutdownAndClearEverything()
    }
  }

  @Test
  fun `test download image`() {
    runBlocking {
      val url = imageUrls[0]
      val image = defaultImageLoader.newRequest()
        .load(url)
        .getAsync()
        .await()

      assertNotNull(image)
      assertEquals(1, listFiles().size)

      val cacheEntries = readCacheInfoFile()
      assertEquals(1, cacheEntries.size)

      for (cacheEntry in cacheEntries) {
        assertEquals(url, cacheEntry.url)
      }
    }
  }

  @Test
  fun `test download image once and then get it from cache`() {
    runBlocking {
      val url = imageUrls[0]

      val time1 = measureTimeMillis {
        val image = defaultImageLoader.newRequest()
          .load(url)
          .getAsync()
          .await()
        assertNotNull(image)
      }

      val time2 = measureTimeMillis {
        val image = defaultImageLoader.newRequest()
          .load(url)
          .getAsync()
          .await()
        assertNotNull(image)
      }

      println("timeToGetFromCache = $time2, timeToGetFromServer = $time1")
      assertTrue(time2 < time1)

      assertEquals(1, listFiles().size)

      val cacheEntries = readCacheInfoFile()
      assertEquals(1, cacheEntries.size)

      for (cacheEntry in cacheEntries) {
        assertEquals(url, cacheEntry.url)
      }
    }
  }

  @Test
  fun `should not make a network request to get an image if there is already one in progress`() {
    runBlocking {
      val images = listOf(imageUrls[0], imageUrls[0], imageUrls[0], imageUrls[0])
        .map {
          defaultImageLoader.newRequest()
            .load(it)
            .getAsync()
        }
        .map { it.await() }

      assertEquals(1, images.count { it != null })
      assertEquals(3, images.count { it == null })
    }
  }

  @Test
  fun `test download images with not enough space in the cache`() {
    runBlocking {
      val images = imageUrls
        .map {
          imageLoaderWithSmallCache.newRequest()
            .load(it)
            .getAsync()
        }
        .map { it.await() }

      delay(1000)

      assertEquals(7, images.size)
      assertEquals(false, images.any { it == null })

      val files = listFiles()

      files.forEach { println("filename = ${it.name}") }

      assertEquals(1, files.size)
      val cacheEntries = readCacheInfoFile()

      assertEquals(1, cacheEntries.size)
    }
  }

  @Test
  fun `test download bad url`() {
    runBlocking {
      val image = defaultImageLoader.newRequest()
        .load("test_non_existent_url.ru")
        .getAsync()
        .await()

      assertNull(image)
      assertTrue(listFiles().isEmpty())
      assertTrue(readCacheInfoFile().isEmpty())
    }
  }

  @Test
  fun `test download image, apply transformations and save original image`() {
    repeat(5) {
      runBlocking {
        val image = defaultImageLoader.newRequest()
          .load(imageUrls[0])
          .transformers(
            TransformerBuilder()
              .centerCrop(100, 100))
          .saveStrategy(SaveStrategy.SaveOriginalImage)
          .getAsync()
          .await()

        assertNotNull(image)
        assertEquals(100, image.width.toInt())
        assertEquals(100, image.height.toInt())

        assertEquals(1, listFiles().size)

        val cacheEntries = readCacheInfoFile()
        assertEquals(1, cacheEntries.size)

        val cacheEntry = cacheEntries[0]
        assertTrue(cacheEntry.appliedTransformations.isEmpty())
      }

      runBlocking {
        val image = defaultImageLoader.newRequest()
          .load(imageUrls[0])
          .transformers(
            TransformerBuilder()
              .centerCrop(100, 100))
          .saveStrategy(SaveStrategy.SaveOriginalImage)
          .getAsync()
          .await()

        assertNotNull(image)
        assertEquals(100, image.width.toInt())
        assertEquals(100, image.height.toInt())

        assertEquals(1, listFiles().size)

        val cacheEntries = readCacheInfoFile()
        assertEquals(1, cacheEntries.size)

        val cacheEntry = cacheEntries[0]
        assertTrue(cacheEntry.appliedTransformations.isEmpty())
      }
    }
  }

  @Test
  fun `test download image, apply transformations and save transformed image`() {
    repeat(5) {
      runBlocking {
        val image = defaultImageLoader.newRequest()
          .load(imageUrls[0])
          .transformers(
            TransformerBuilder()
              .centerCrop(100, 100))
          .saveStrategy(SaveStrategy.SaveTransformedImage)
          .getAsync()
          .await()

        assertNotNull(image)
        assertEquals(100, image.width.toInt())
        assertEquals(100, image.height.toInt())

        assertEquals(1, listFiles().size)

        val cacheEntries = readCacheInfoFile()
        assertEquals(1, cacheEntries.size)

        val cacheEntry = cacheEntries[0]
        assertEquals(1, cacheEntry.appliedTransformations.size)
        assertEquals(TransformationType.CenterCrop, cacheEntry.appliedTransformations[0])
      }

      runBlocking {
        val image = defaultImageLoader.newRequest()
          .load(imageUrls[0])
          .transformers(
            TransformerBuilder()
              .centerCrop(100, 100))
          .saveStrategy(SaveStrategy.SaveTransformedImage)
          .getAsync()
          .await()

        assertNotNull(image)
        assertEquals(100, image.width.toInt())
        assertEquals(100, image.height.toInt())

        assertEquals(1, listFiles().size)

        val cacheEntries = readCacheInfoFile()
        assertEquals(1, cacheEntries.size)

        val cacheEntry = cacheEntries[0]
        assertEquals(1, cacheEntry.appliedTransformations.size)
        assertEquals(TransformationType.CenterCrop, cacheEntry.appliedTransformations[0])
      }
    }
  }

  @Test
  fun `test override CenterCrop transformation's result image width and height`() {
    runBlocking {
      val image = defaultImageLoader.newRequest()
        .load(imageUrls[0])
        .transformers(
          TransformerBuilder()
            .centerCrop(200, 200)
            .resize(150, 150)
        )
        .saveStrategy(SaveStrategy.SaveTransformedImage)
        .getAsync()
        .await()

      assertNotNull(image)
      assertEquals(150, image.width.toInt())
      assertEquals(150, image.height.toInt())

      assertEquals(1, listFiles().size)

      val cacheEntries = readCacheInfoFile()
      assertEquals(1, cacheEntries.size)

      val cacheEntry = cacheEntries[0]
      assertEquals(2, cacheEntry.appliedTransformations.size)
      assertEquals(TransformationType.CenterCrop, cacheEntry.appliedTransformations[0])
      assertEquals(TransformationType.Resize, cacheEntry.appliedTransformations[1])
    }
  }
}