package cache

import java.io.File
import java.lang.NumberFormatException
import java.lang.RuntimeException
import java.nio.file.Files

class DiskCache(
  private val maxDiskCacheSize: Long,
  private val cacheDir: File
) : Cache<String, File> {
  private val separator = ";"
  private val cache = mutableMapOf<String, CacheEntry>()

  private lateinit var cacheInfoFile: File

  fun init() {
    cacheInfoFile = File(cacheDir, "disk-cache.dat")
    if (cacheInfoFile.exists()) {
      readCacheFile()
    } else {
      if (!cacheInfoFile.createNewFile()) {
        throw RuntimeException("Could not create cache info file!")
      }
    }
  }

  override fun store(key: String, value: File) {
    val fileLength = value.length()
    val totalCacheSize = calculateTotalCacheSize() + fileLength

    if (totalCacheSize > maxDiskCacheSize) {
      println("Disk cache size exceeded ($totalCacheSize > $maxDiskCacheSize)")
      val clearAtLeast = Math.max((maxDiskCacheSize * 0.3).toLong(), fileLength)

      println("Removing images to clear at least ${clearAtLeast} bytes in cache")
      val lastAddedEntries = getLastAddedCacheEntries(clearAtLeast)

      for (entry in lastAddedEntries) {
        println("Removing CacheEntry: $entry")
        remove(entry.url)
      }
    }

    cache[key] = CacheEntry(key, value.name, fileLength, System.currentTimeMillis())
    updateCacheInfoFile()
  }

  override fun get(key: String): File? {
    if (cache.containsKey(key)) {
      val cacheEntry = cache[key]!!
      val fullImagePath = File(cacheDir, cacheEntry.fileName)

      if (!fullImagePath.exists()) {
        remove(key)
        return null
      }

      return fullImagePath
    }

    return null
  }

  override fun contains(key: String): Boolean {
    return cache.containsKey(key)
  }

  override fun remove(key: String) {
    val cacheEntry = cache[key]
    if (cacheEntry == null) {
      return
    }

    val path = File(cacheDir, cacheEntry.fileName).toPath()
    Files.deleteIfExists(path)

    cache.remove(key)
    updateCacheInfoFile()
  }

  override fun clear() {
    cache.clear()

    for (file in cacheDir.listFiles()) {
      if (file.exists() && file.isFile) {
        Files.deleteIfExists(file.toPath())
      }
    }

    cacheDir.delete()
  }

  private fun getLastAddedCacheEntries(clearAtLeast: Long): List<CacheEntry> {
    val sortedCacheEntries = cache.values
      .sortedBy { it.addedOn }

    var accumulatedSize = 0L
    val cacheEntryList = mutableListOf<CacheEntry>()

    for (entry in sortedCacheEntries) {
      if (accumulatedSize >= clearAtLeast) {
        break
      }

      accumulatedSize += entry.fileSize
      cacheEntryList += entry
    }

    return cacheEntryList
  }

  private fun calculateTotalCacheSize(): Long {
    if (cache.values.isEmpty()) {
      return 0L
    }

    return cache.values
      .map { it.fileSize }
      .reduce { acc, len -> acc + len }
  }

  private fun readCacheFile() {
    val lines = cacheInfoFile.readLines()
    var hasCorruptedEntries = false

    cache.clear()

    for (line in lines) {
      val split = line.split(separator)

      if (split.size != 4) {
        hasCorruptedEntries = true
        continue
      }

      val (url, fileName, fileSizeStr, addedOnStr) = split

      if (url.isEmpty()) {
        hasCorruptedEntries = true
        continue
      }

      if (fileName.isEmpty()) {
        hasCorruptedEntries = true
        continue
      }

      val fileSize = try {
        fileSizeStr.toLong()
      } catch (error: NumberFormatException) {
        hasCorruptedEntries = true
        continue
      }

      val addedOn = try {
        addedOnStr.toLong()
      } catch (error: NumberFormatException) {
        hasCorruptedEntries = true
        continue
      }

      if (cache.containsKey(url)) {
        hasCorruptedEntries = true
        continue
      }

      cache[url] = CacheEntry(url, fileName, fileSize, addedOn)
    }

    if (getAllCachedFilesInCacheDir().size != lines.size) {
      hasCorruptedEntries = true
    }

    if (hasCorruptedEntries) {
      removeCorruptedEntries()
    }
  }

  private fun removeCorruptedEntries() {
    val filesInCacheDirectory = getAllCachedFilesInCacheDir()
    val toDeleteCacheEntries = mutableSetOf<CacheEntry>()

    for (file in filesInCacheDirectory) {
      val fileName = file.name

      for (cacheEntry in cache.values) {
        if (fileName == cacheEntry.fileName) {
          break
        }

        toDeleteCacheEntries += cacheEntry
      }
    }

    for (toDeleteEntry in toDeleteCacheEntries) {
      cache.remove(toDeleteEntry.url)

      val path = File(cacheDir, toDeleteEntry.fileName).toPath()
      Files.deleteIfExists(path)
    }

    updateCacheInfoFile()
  }

  private fun getAllCachedFilesInCacheDir(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name }
  }

  private fun updateCacheInfoFile() {
    val outString = buildString {
      for ((_, value) in cache) {
        appendln("${value.url}${separator}${value.fileName}${separator}${value.fileSize}${separator}${value.addedOn}")
      }
    }

    cacheInfoFile.writeText(outString)
  }

  data class CacheEntry(
    val url: String,
    val fileName: String,
    val fileSize: Long,
    val addedOn: Long
  )
}