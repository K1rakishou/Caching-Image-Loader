package cache

import transformations.TransformationType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class DiskCache(
  private val maxDiskCacheSize: Long,
  private val cacheDir: File,
  private val showDebugLog: Boolean
) : Cache<String, CacheValue> {
  private val separator = ";"
  private val appliedTransformSeparator = ","
  private val cacheEntryMap = ConcurrentHashMap<String, CacheEntry>()

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

  private fun debugPrint(msg: String) {
    if (showDebugLog) {
      println(msg)
    }
  }

  private fun readCacheFile() {
    val lines = cacheInfoFile.readLines()
    var hasCorruptedEntries = false

    cacheEntryMap.clear()

    for (line in lines) {
      val split = line.split(separator)

      if (split.size != 5) {
        hasCorruptedEntries = true
        continue
      }

      val (url, fileName, fileSizeStr, addedOnStr, appliedTransformationsStr) = split

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

      if (cacheEntryMap.containsKey(url)) {
        hasCorruptedEntries = true
        continue
      }

      cacheEntryMap[url] = CacheEntry(url, fileName, fileSize, addedOn, transformations)
    }

    if (getAllCachedFilesInCacheDir().size != lines.size) {
      hasCorruptedEntries = true
    }

    if (hasCorruptedEntries) {
      removeCorruptedEntries()
    }
  }

  override fun store(key: String, value: CacheValue) {
    val file = value.file
    val fileLength = file.length()
    val totalCacheSize = calculateTotalCacheSize() + fileLength

    if (totalCacheSize > maxDiskCacheSize) {
      debugPrint("Disk cache size exceeded ($totalCacheSize > $maxDiskCacheSize)")
      val clearAtLeast = Math.max((maxDiskCacheSize * 0.3).toLong(), fileLength)

      debugPrint("Removing images to clear at least ${clearAtLeast} bytes in cache")
      val lastAddedEntries = getLastAddedCacheEntries(clearAtLeast)

      for (entry in lastAddedEntries) {
        debugPrint("Removing CacheEntry: $entry")
        remove(entry.url)
      }
    }

    cacheEntryMap[key] = CacheEntry(key, file.name, fileLength, System.currentTimeMillis(), value.appliedTransformations)
    updateCacheInfoFile()
  }

  override fun get(key: String): CacheValue? {
    val cacheEntry = cacheEntryMap[key]
    if (cacheEntry != null) {
      val fullImagePath = File(cacheDir, cacheEntry.fileName)

      if (!fullImagePath.exists()) {
        remove(key)
        return null
      }

      return CacheValue(fullImagePath, cacheEntry.appliedTransformations)
    }

    return null
  }

  override fun remove(key: String) {
    val cacheEntry = cacheEntryMap[key]
    if (cacheEntry == null) {
      return
    }

    cacheEntryMap.remove(key)
    updateCacheInfoFile()

    val path = File(cacheDir, cacheEntry.fileName).toPath()
    Files.deleteIfExists(path)
  }

  override fun clear() {
    cacheEntryMap.clear()

    for (file in cacheDir.listFiles()) {
      if (file.exists() && file.isFile) {
        Files.deleteIfExists(file.toPath())
      }
    }

    cacheDir.delete()
  }

  private fun getLastAddedCacheEntries(clearAtLeast: Long): List<CacheEntry> {
    val sortedCacheEntries = cacheEntryMap.values
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
    if (cacheEntryMap.isEmpty()) {
      return 0L
    }

    return cacheEntryMap.values
      .map { it.fileSize }
      .reduce { acc, len -> acc + len }
  }

  private fun removeCorruptedEntries() {
    val filesInCacheDirectory = getAllCachedFilesInCacheDir()
    val toDeleteCacheEntries = mutableSetOf<CacheEntry>()
    val fileToDeletePaths = mutableListOf<Path>()

    for (file in filesInCacheDirectory) {
      val fileName = file.name

      for (cacheEntry in cacheEntryMap.values) {
        if (fileName == cacheEntry.fileName) {
          break
        }

        toDeleteCacheEntries += cacheEntry
      }
    }

    for (toDeleteEntry in toDeleteCacheEntries) {
      cacheEntryMap.remove(toDeleteEntry.url)
      fileToDeletePaths.add(File(cacheDir, toDeleteEntry.fileName).toPath())
    }

    fileToDeletePaths.forEach {
      Files.deleteIfExists(it)
    }

    updateCacheInfoFile()
  }

  private fun getAllCachedFilesInCacheDir(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name }
  }

  private fun updateCacheInfoFile() {
    val outString = buildString(256) {
      for ((_, value) in cacheEntryMap) {
        val appliedTransformations = value.appliedTransformations
          .map { it.type }
          .joinToString(appliedTransformSeparator)

        append(value.url)
        append(separator)
        append(value.fileName)
        append(separator)
        append(value.fileSize)
        append(separator)
        append(value.addedOn)
        append(separator)
        append("($appliedTransformations)")
        appendln()
      }
    }

    cacheInfoFile.writeText(outString)
  }
}