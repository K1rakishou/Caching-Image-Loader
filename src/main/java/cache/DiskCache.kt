package cache

import java.io.File
import java.lang.NumberFormatException
import java.lang.RuntimeException

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
    if (calculateTotalCacheSize() > maxDiskCacheSize) {
      val lastAddedEntries = getLastAddedCacheEntries(value.length())

      for (entry in lastAddedEntries) {
        remove(entry.url)
      }
    }

    cache[key] = CacheEntry(key, value.name, value.length(), System.currentTimeMillis())
    saveToCacheFile()
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

    val fullPath = File(cacheDir, cacheEntry.fileName)
    fullPath.delete()

    cache.remove(key)
    saveToCacheFile()
  }

  private fun getLastAddedCacheEntries(sizeAtLeast: Long): List<CacheEntry> {
    val sortedCacheEntries = cache.values
      .sortedByDescending { it.addedOn }

    var accumulatedSize = 0L
    val cacheEntryList = mutableListOf<CacheEntry>()

    for (entry in sortedCacheEntries) {
      if (accumulatedSize > sizeAtLeast) {
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

    return cache.values.map { it.fileSize }.reduce { acc, l -> acc + l }
  }

  private fun readCacheFile() {
    val lines = cacheInfoFile.readLines()
    var hasCorruptedEntries = false

    cache.clear()

    for (line in lines) {
      var isCorrupted = false
      val (url, fileName, fileSizeStr, addedOnStr) = line.split(separator)

      if (url.isEmpty()) {
        isCorrupted = true
      }

      if (fileName.isEmpty()) {
        isCorrupted = true
      }

      val fileSize = try {
        fileSizeStr.toLong()
      } catch (error: NumberFormatException) {
        isCorrupted = true
        0L
      }

      val addedOn = try {
        addedOnStr.toLong()
      } catch (error: NumberFormatException) {
        isCorrupted = true
        0L
      }

      if (isCorrupted) {
        hasCorruptedEntries = true
        continue
      }

      val key = cacheDir.absolutePath + "\\" + fileName

      if (cache.containsKey(key)) {
        hasCorruptedEntries = true
        continue
      }

      cache[key] = CacheEntry(url, fileName, fileSize, addedOn)
    }

    if (hasCorruptedEntries) {
      removeCorruptedEntries()
    }
  }

  private fun saveToCacheFile() {
    val outString = buildString {
      for ((_, value) in cache) {
        appendln("${value.url}${separator}${value.fileName}${separator}${value.fileSize}${separator}${value.addedOn}")
      }
    }

    cacheInfoFile.writeText(outString)
  }

  private fun removeCorruptedEntries() {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
  }

  data class CacheEntry(
    val url: String,
    val fileName: String,
    val fileSize: Long,
    val addedOn: Long
  )
}