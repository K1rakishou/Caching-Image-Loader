package cache

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import transformations.TransformationType
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiskCacheTest {
  private val cacheDir = File(System.getProperty("user.dir"), "\\image-cache")
  private val tempFilesDir = File(System.getProperty("user.dir"), "\\temp-files")
  private val cacheInfoFile = File(cacheDir, "disk-cache.dat")
  private val cache = DiskCache(128 * 1024, cacheDir, false)
  private val smallCache = DiskCache(1, cacheDir, false)

  private val runsCount = 16
  private val concurrency = 128

  private fun deleteFile(file: File) {
    if (!file.exists() || !file.isFile) {
      return
    }

    val path = file.toPath()
    Files.deleteIfExists(path)
  }

  fun listFiles(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name }
  }

  fun readCacheInfoFile(): List<CacheInfoRecord> {
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

  private fun runConcurrently(maxConcurrency: Int, block: suspend (Int) -> Unit) {
    runBlocking {
      (0 until maxConcurrency).map { index ->
        GlobalScope.async(Dispatchers.IO) {
          block(index)
        }
      }.forEach { it.await() }
    }
  }

  private fun createRandomFile(threadIndex: Int): File {
    val text = threadIndex.toString().padStart(3, '0')

    return File(tempFilesDir, "\\${text}_file")
      .apply {
        createNewFile()
        writeText(text)
      }
  }

  @Before
  fun init() {
    tempFilesDir.mkdirs()
    cacheDir.mkdirs()

    cache.init()
    smallCache.init()
  }

  @After
  fun tearDown() {
    tempFilesDir.listFiles()
      ?.forEach { deleteFile(it) }

    runBlocking {
      cache.clear()
      smallCache.clear()
    }
  }

  @Test
  fun `test store in normal cache concurrently`() {
    repeat(runsCount) {
      println("run $it out of $runsCount")

      listFiles().forEach { file -> deleteFile(file) }

      runConcurrently(concurrency) { threadIndex ->
        val file = createRandomFile(threadIndex)
        cache.store(threadIndex.toString(), CacheValue(file, emptyArray()))

        Files.deleteIfExists(file.toPath())
      }

      assertEquals(concurrency, listFiles().size)

      val cacheInfoFile = readCacheInfoFile()
      assertEquals(concurrency, cacheInfoFile.size)

      val sorted = cacheInfoFile.sortedBy { it.url.toInt() }
      val filesOnDisk = listFiles()

      for ((index, entry) in sorted.withIndex()) {
        assertEquals(index, entry.url.toInt())

        assertTrue(filesOnDisk.any { fileOnDisk -> fileOnDisk.absolutePath == entry.cachedFile.absolutePath })
      }
    }
  }

  @Test
  fun `test store in small cache concurrently`() {
    repeat(runsCount) { index ->
      println("Run #$index out of $runsCount")

      runConcurrently(concurrency) { threadIndex ->
        val file = createRandomFile(threadIndex)
        smallCache.store(threadIndex.toString(), CacheValue(file, emptyArray()))

        Files.deleteIfExists(file.toPath())
      }

      val filesCount = listFiles().size
      assertEquals(1, filesCount)

      val cacheInfoFile = readCacheInfoFile()
      assertEquals(1, cacheInfoFile.size)
    }
  }
}