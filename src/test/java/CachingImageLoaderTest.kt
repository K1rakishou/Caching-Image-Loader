import cache.CacheEntry
import core.SaveStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import transformers.TransformationType
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachingImageLoaderTest {
  private lateinit var imageLoader: CachingImageLoader
  private val cacheDir = File(System.getProperty("user.dir"), "\\image-cache")
  private val cacheInfoFile = File(cacheDir, "disk-cache.dat")

  private val imageUrls = listOf(
    "https://i.imgur.com/cc2M7sK.png",
    "https://i.imgur.com/aCedFRM.jpg",
    "https://i.imgur.com/858QmKq.jpg",
    "https://i.imgur.com/aHeHrkH.jpg",
    "https://i.imgur.com/BrainlI.jpg",
    "https://i.imgur.com/s0FJbU6.png",
    "https://i.imgur.com/FNhvKzq.jpg"
  )

  @After
  fun tearDown() {
    imageLoader.shutdownAndClearEverything()
  }

  private fun listFiles(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name }
  }

  private fun readCacheInfoFile(): List<CacheEntry> {
    val lines = cacheInfoFile.readLines()
    val cacheEntries = mutableListOf<CacheEntry>()

    for (line in lines) {
      val split = line.split(";")
      val (url, fileName, fileSizeStr, addedOnStr, appliedTransformationsStr) = split

      assertTrue(url.isNotEmpty())
      assertTrue(fileName.isNotEmpty())
      val fileSize = fileSizeStr.toLong()
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

      cacheEntries += CacheEntry(url, fileName, fileSize, addedOn, transformations)
    }

    return cacheEntries
  }

  @Test
  fun `test download image`() {
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val url = imageUrls[0]
      val image = imageLoader.newRequest()
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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val url = imageUrls[0]

      val time1 = measureTimeMillis {
        val image = imageLoader.newRequest()
          .load(url)
          .getAsync()
          .await()
        assertNotNull(image)
      }

      val time2 = measureTimeMillis {
        val image = imageLoader.newRequest()
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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val images = listOf(imageUrls[0], imageUrls[0], imageUrls[0], imageUrls[0])
        .map {
          imageLoader.newRequest()
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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, maxDiskCacheSize = 128 * 1024, showDebugLog = false)

    runBlocking {
      val images = imageUrls
        .map {
          imageLoader.newRequest()
            .load(it)
            .getAsync()
        }
        .map { it.await() }

      assertEquals(7, images.size)
      assertEquals(false, images.any { it == null })
      assertEquals(1, listFiles().size)
      val cacheEntries = readCacheInfoFile()

      assertEquals(1, cacheEntries.size)
    }
  }

  @Test
  fun `test download bad url`() {
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val image = imageLoader.newRequest()
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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val image = imageLoader.newRequest()
        .load(imageUrls[0])
        .transformers(
          CachingImageLoader.TransformerBuilder()
            .fitCenter(100, 100))
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
      val image = imageLoader.newRequest()
        .load(imageUrls[0])
        .transformers(
          CachingImageLoader.TransformerBuilder()
            .fitCenter(100, 100))
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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val image = imageLoader.newRequest()
        .load(imageUrls[0])
        .transformers(
          CachingImageLoader.TransformerBuilder()
            .fitCenter(100, 100))
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
      assertEquals(TransformationType.FitCenter, cacheEntry.appliedTransformations[0])
    }

    runBlocking {
      val image = imageLoader.newRequest()
        .load(imageUrls[0])
        .transformers(
          CachingImageLoader.TransformerBuilder()
            .fitCenter(100, 100))
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
      assertEquals(TransformationType.FitCenter, cacheEntry.appliedTransformations[0])
    }
  }

  @Test
  fun `test override FitCenter transformator's result image width and height`() {
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, showDebugLog = false)

    runBlocking {
      val image = imageLoader.newRequest()
        .load(imageUrls[0])
        .transformers(
          CachingImageLoader.TransformerBuilder()
            .fitCenter(200, 200)
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
      assertEquals(TransformationType.FitCenter, cacheEntry.appliedTransformations[0])
      assertEquals(TransformationType.Resize, cacheEntry.appliedTransformations[1])
    }
  }
}