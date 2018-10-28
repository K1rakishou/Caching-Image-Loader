import builders.TransformationBuilder
import cache.CacheInfoRecord
import core.CachingImageLoader
import core.SaveStrategy
import http.HttpClientFacade
import http.ResponseData
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import transformations.TransformationType
import java.io.File
import java.util.concurrent.CompletableFuture
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
      val future = CompletableFuture<ResponseData?>()

      val contentType = when {
        fileName.endsWith("png") -> "image/png"
        fileName.endsWith("jpg") -> "image/jpg"
        else -> {
          println("Unknown extension ${fileName}")
          future.complete(null)
          return@doAnswer future
        }
      }

      future.complete(ResponseData(contentType, file.readBytes()))
      return@doAnswer future

    }.`when`(fakeHttpClient).fetchImage(Mockito.anyString())

    defaultImageLoader = CachingImageLoader(
      client = fakeHttpClient,
      dispatcher = Dispatchers.Unconfined,
      showDebugLog = false
    )

    imageLoaderWithSmallCache = CachingImageLoader(
      client = fakeHttpClient,
      dispatcher = Dispatchers.Unconfined,
      maxDiskCacheSize = 128 * 1024,
      showDebugLog = false
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
    repeat(10) {
      runBlocking {
        val url = imageUrls[0]

        val image1 = defaultImageLoader.newRequest()
          .load(url)
          .getAsync()
          .await()
        assertNotNull(image1)

        val image2 = defaultImageLoader.newRequest()
          .load(url)
          .getAsync()
          .await()
        assertNotNull(image2)

        assertEquals(1, listFiles().size)

        val cacheEntries = readCacheInfoFile()
        assertEquals(1, cacheEntries.size)

        for (cacheEntry in cacheEntries) {
          assertEquals(url, cacheEntry.url)
        }
      }
    }
  }

  @Test
  fun `should not make a network request to get an image if there is already one in progress`() {
    repeat(10) {
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
  }

  @Test
  fun `test download images concurrently with enough space in the cache`() {
    repeat(5) {
      runBlocking {
        val images = imageUrls
          .map {
            GlobalScope.async {
              defaultImageLoader.newRequest()
                .load(it)
                .getAsync()
            }
          }
          .map { it.await() }
          .map { it.await() }

        assertEquals(7, images.size)
        assertEquals(false, images.any { it == null })

        val files = listFiles()

        assertEquals(7, files.size)
        val cacheEntries = readCacheInfoFile()

        assertEquals(7, cacheEntries.size)
      }
    }
  }

  @Test
  fun `test download images concurrently with not enough space in the cache`() {
    repeat(5) {
      runBlocking {
        val images = imageUrls
          .map {
            GlobalScope.async {
              imageLoaderWithSmallCache.newRequest()
                .load(it)
                .getAsync()
            }
          }
          .map { it.await() }
          .map { it.await() }

        assertEquals(7, images.size)
        assertEquals(false, images.any { it == null })

        val files = listFiles()

        assertEquals(1, files.size)
        val cacheEntries = readCacheInfoFile()

        assertEquals(1, cacheEntries.size)
      }
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
    runBlocking {
      val image = defaultImageLoader.newRequest()
        .load(imageUrls[0])
        .transformations(
          TransformationBuilder()
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
        .transformations(
          TransformationBuilder()
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

  @Test
  fun `test download image, apply transformations and save transformed image`() {
    runBlocking {
      val image = defaultImageLoader.newRequest()
        .load(imageUrls[0])
        .transformations(
          TransformationBuilder()
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
        .transformations(
          TransformationBuilder()
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

  @Test
  fun `test override CenterCrop transformation's result image width and height`() {
    runBlocking {
      val image = defaultImageLoader.newRequest()
        .load(imageUrls[0])
        .transformations(
          TransformationBuilder()
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