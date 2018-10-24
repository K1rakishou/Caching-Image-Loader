import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
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

  private val imageSizes = hashMapOf(
    "https://i.imgur.com/s0FJbU6.png" to 390712L,   //391kb
    "https://i.imgur.com/aHeHrkH.jpg" to 197132L,   //197kb
    "https://i.imgur.com/BrainlI.jpg" to 417705L,   //418kb
    "https://i.imgur.com/FNhvKzq.jpg" to 156178L    //156kb
  )

  private val imageUrls = listOf(
    "https://i.imgur.com/s0FJbU6.png",
    "https://i.imgur.com/aHeHrkH.jpg",
    "https://i.imgur.com/BrainlI.jpg",
    "https://i.imgur.com/FNhvKzq.jpg"
  )

  @After
  fun tearDown() {
    imageLoader.shutdownAndClearEverything()
  }

  data class CacheEntry(
    val url: String,
    val fileName: String,
    val fileSize: Long,
    val addedOn: Long
  )

  private fun listFiles(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name }
  }

  private fun readCacheInfoFile(): List<CacheEntry> {
    val lines = cacheInfoFile.readLines()
    val cacheEntries = mutableListOf<CacheEntry>()

    for (line in lines) {
      val split = line.split(";")
      val (url, fileName, fileSizeStr, addedOnStr) = split

      if (url.isEmpty()) {
        continue
      }

      if (fileName.isEmpty()) {
        continue
      }

      val fileSize = try {
        fileSizeStr.toLong()
      } catch (error: NumberFormatException) {
        continue
      }

      val addedOn = try {
        addedOnStr.toLong()
      } catch (error: NumberFormatException) {
        continue
      }

      cacheEntries += CacheEntry(url, fileName, fileSize, addedOn)
    }

    return cacheEntries
  }

  @Test
  fun `test download image`() {
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined)

    runBlocking {
      val url = imageUrls[0]
      val image = imageLoader.getImage(url).await()

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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined)

    runBlocking {
      val url = imageUrls[0]

      val time1 = measureTimeMillis {
        val image = imageLoader.getImage(url).await()
        assertNotNull(image)
      }

      val time2 = measureTimeMillis {
        val image = imageLoader.getImage(imageUrls[0]).await()
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
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined)

    runBlocking {
      val images = listOf(imageUrls[0], imageUrls[0], imageUrls[0], imageUrls[0])
        .map { imageLoader.getImage(it) }
        .map { it.await() }

      assertEquals(1, images.count { it != null } )
      assertEquals(3, images.count { it == null } )
    }
  }

  @Test
  fun `test download images with not enough space in the cache`() {
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined, maxDiskCacheSize = 512 * 1024)

    runBlocking {
      val images = imageUrls
        .map { imageLoader.getImage(it) }
        .map { it.await() }

      assertEquals(4, images.size)
      assertEquals(false, images.any { it == null })
      assertEquals(1, listFiles().size)

      val cacheEntries = readCacheInfoFile()

      assertEquals(1, cacheEntries.size)
      assertEquals(imageUrls.last(), cacheEntries[0].url)
    }
  }

  @Test
  fun `test download bad url`() {
    imageLoader = CachingImageLoader(dispatcher = Dispatchers.Unconfined)

    runBlocking {
      val image = imageLoader.getImage("test.ru").await()

      assertNull(image)
      assertTrue(listFiles().isEmpty())
      assertTrue(readCacheInfoFile().isEmpty())
    }
  }
}