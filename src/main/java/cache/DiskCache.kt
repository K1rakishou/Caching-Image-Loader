package cache

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import transformations.TransformationType
import java.io.File
import java.nio.file.Files

class DiskCache(
  private val maxDiskCacheSize: Long,
  private val cacheDir: File,
  private val showDebugLog: Boolean
) : Cache<String, CacheValue>, CoroutineScope {
  private val separator = ";"
  private val appliedTransformSeparator = ","
  private val job = Job()
  private val cacheInfoFile = File(cacheDir, "disk-cache.dat")

  private lateinit var cacheOperationsActor: SendChannel<CacheOperation>

  override val coroutineContext = job

  private fun cacheOperationsActor(): SendChannel<CacheOperation> {
    val cacheEntryMap = hashMapOf<String, CacheInfoRecord>()

    return actor {
      consumeEach { operation ->
        when (operation) {
          is CacheOperation.ReadCacheInfoFile -> {
            innerReadCacheFile(cacheEntryMap)
            operation.result.complete(Unit)
          }
          is CacheOperation.Store -> {
            innerStore(operation.key, operation.cacheValue.file, operation.cacheValue.appliedTransformations, cacheEntryMap)
            operation.result.complete(Unit)
          }
          is CacheOperation.Get -> {
            val cacheValue = innerGet(operation.key, cacheEntryMap)
            operation.result.complete(cacheValue)
          }
          is CacheOperation.Contains -> {
            val contains = innerContains(operation.key, cacheEntryMap)
            operation.result.complete(contains)
          }
          is CacheOperation.GetOldest -> {
            val oldest = getOldestCacheEntries(operation.sizeAtLest, cacheEntryMap)
            operation.result.complete(oldest)
          }
          is CacheOperation.Size -> {
            operation.result.complete(cacheEntryMap.size)
          }
          is CacheOperation.Remove -> {
            innerRemove(operation.key, cacheEntryMap)
            operation.result.complete(Unit)
          }
          is CacheOperation.EvictOldest -> {
            val oldest = innerEvictOldest(cacheEntryMap)
            operation.result.complete(oldest)
          }
          is CacheOperation.Clear -> {
            innerClear(cacheEntryMap)
            operation.result.complete(Unit)
          }
        }
      }
    }
  }

  /**
   * Public operations
   * */

  fun init() {
    runBlocking(job) {
      cacheOperationsActor = cacheOperationsActor()

      if (cacheInfoFile.exists()) {
        readCacheInfoFile()
      } else {
        if (!cacheInfoFile.createNewFile()) {
          throw RuntimeException("Could not create cache info file!")
        }
      }
    }
  }

  private suspend fun readCacheInfoFile() {
    val result = CompletableDeferred<Unit>()
    cacheOperationsActor.send(CacheOperation.ReadCacheInfoFile(result))
    result.await()
  }

  override suspend fun store(key: String, value: CacheValue) {
    val result = CompletableDeferred<Unit>()
    cacheOperationsActor.send(CacheOperation.Store(key, value, result))
    result.await()
  }

  override suspend fun contains(key: String): Boolean {
    val result = CompletableDeferred<Boolean>()
    cacheOperationsActor.send(CacheOperation.Contains(key, result))
    return result.await()
  }

  override suspend fun get(key: String): CacheValue? {
    val result = CompletableDeferred<CacheValue?>()
    cacheOperationsActor.send(CacheOperation.Get(key, result))
    return result.await()
  }

  override suspend fun remove(key: String) {
    val result = CompletableDeferred<Unit>()
    cacheOperationsActor.send(CacheOperation.Remove(key, result))
    result.await()
  }

  override suspend fun size(): Int {
    val result = CompletableDeferred<Int>()
    cacheOperationsActor.send(CacheOperation.Size(result))
    return result.await()
  }

  override suspend fun evictOldest(): CacheValue? {
    val result = CompletableDeferred<CacheValue?>()
    cacheOperationsActor.send(CacheOperation.EvictOldest(result))
    return result.await()
  }

  override suspend fun clear() {
    val result = CompletableDeferred<Unit>()
    cacheOperationsActor.send(CacheOperation.Clear(result))
    result.await()
  }

  /**
   * Inner operations
   * */

  private fun innerEvictOldest(cacheEntryMap: HashMap<String, CacheInfoRecord>): CacheValue? {
    if (cacheEntryMap.isEmpty()) {
      return null
    }

    val oldest = cacheEntryMap
      .minBy { it.value.lastAccessTime }

    if (oldest == null) {
      return null
    }

    innerRemove(oldest.key, cacheEntryMap)
    return CacheValue(oldest.value.cachedFile, oldest.value.appliedTransformations)
  }

  private fun innerClear(cacheInfoRecordMap: HashMap<String, CacheInfoRecord>) {
    cacheInfoRecordMap.clear()

    val files = getAllCachedFilesInCacheDir()
    for (file in files) {
      deleteFile(file)
    }

    innerUpdateCacheInfoFile(cacheInfoRecordMap)
  }

  private fun innerRemoveMany(keyList: List<String>, cacheInfoRecordMap: HashMap<String, CacheInfoRecord>) {
    val entriesToRemove = keyList
      .map { cacheInfoRecordMap[it]!! }

    for (entry in entriesToRemove) {
      deleteFile(entry.cachedFile)
      cacheInfoRecordMap.remove(entry.url)
    }

    innerUpdateCacheInfoFile(cacheInfoRecordMap)
  }

  private fun innerRemove(key: String, cacheInfoRecordMap: HashMap<String, CacheInfoRecord>) {
    val cacheEntry = cacheInfoRecordMap[key]
    if (cacheEntry == null) {
      return
    }

    cacheInfoRecordMap.remove(key)
    innerUpdateCacheInfoFile(cacheInfoRecordMap)

    deleteFile(cacheEntry.cachedFile)
  }

  private fun innerGet(key: String, cacheInfoRecordMap: HashMap<String, CacheInfoRecord>): CacheValue? {
    val cacheEntry = cacheInfoRecordMap[key]
    if (cacheEntry == null) {
      return null
    }

    if (!cacheEntry.cachedFile.exists()) {
      innerRemove(key, cacheInfoRecordMap)
      return null
    }

    //update lastAccessTime on every get operation
    cacheEntry.lastAccessTime = System.nanoTime()

    //and update it in the cacheInfoFile
    innerUpdateCacheInfoFile(cacheInfoRecordMap)

    return CacheValue(cacheEntry.cachedFile, cacheEntry.appliedTransformations)
  }

  private fun innerContains(key: String, cacheInfoRecordMap: HashMap<String, CacheInfoRecord>): Boolean {
    return cacheInfoRecordMap.contains(key)
  }

  private fun innerStore(
    key: String,
    inFile: File,
    appliedTransformations: Array<TransformationType>,
    cacheInfoRecordMap: HashMap<String, CacheInfoRecord>
  ) {
    val fileLength = inFile.length()
    val totalCacheSize = calculateTotalCacheSize(cacheInfoRecordMap) + fileLength

    if (totalCacheSize > maxDiskCacheSize) {
      val clearAtLeast = Math.max((maxDiskCacheSize * 0.3).toLong(), fileLength)
      val lastAddedEntries = getOldestCacheEntries(clearAtLeast, cacheInfoRecordMap)

      val keyList = lastAddedEntries.map { it.url }
      innerRemoveMany(keyList, cacheInfoRecordMap)
    }

    val newFile = createCachedImageFile(key)
    inFile.copyTo(newFile)

    cacheInfoRecordMap[key] = CacheInfoRecord(key, newFile, System.nanoTime(), appliedTransformations)
    innerUpdateCacheInfoFile(cacheInfoRecordMap)
  }

  private fun innerReadCacheFile(cacheInfoRecordMap: HashMap<String, CacheInfoRecord>) {
    val lines = cacheInfoFile.readLines()
    var hasCorruptedEntries = false

    cacheInfoRecordMap.clear()

    if (lines.isEmpty()) {
      return
    }

    for (line in lines) {
      val split = line.split(separator)

      if (split.size != 4) {
        debugPrint("Corrupted cache info file: split.size (${split.size}) != 5")
        hasCorruptedEntries = true
        continue
      }

      val (url, filePath, addedOnStr, appliedTransformationsStr) = split

      if (url.isEmpty()) {
        debugPrint("Corrupted cache info file: url is empty")
        hasCorruptedEntries = true
        continue
      }

      if (filePath.isEmpty()) {
        debugPrint("Corrupted cache info file: filePath is empty")
        hasCorruptedEntries = true
        continue
      }

      val file = File(filePath)
      if (!file.exists() || !file.isFile) {
        debugPrint("Corrupted cache info file: file does not exist or it's not a file")
        hasCorruptedEntries = true
        continue
      }

      val addedOn = try {
        addedOnStr.toLong()
      } catch (error: NumberFormatException) {
        debugPrint("Corrupted cache info file: addedOnStr is not a number")
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

      if (cacheInfoRecordMap.containsKey(url)) {
        debugPrint("Corrupted cache info file: cacheInfoRecordMap already contain the url ($url)")
        hasCorruptedEntries = true
        continue
      }

      cacheInfoRecordMap[url] = CacheInfoRecord(url, file, addedOn, transformations)
    }

    val fileCount = getAllCachedFilesInCacheDir().size
    if (fileCount != lines.size) {
      debugPrint("Corrupted cache info file: count of files in the cache directory ($fileCount) != entries in the cache info file (${lines.size})")
      hasCorruptedEntries = true
    }

    if (hasCorruptedEntries) {
      removeCorruptedEntries(cacheInfoRecordMap)
    }
  }

  private fun innerUpdateCacheInfoFile(cacheInfoRecordMap: HashMap<String, CacheInfoRecord>) {
    val outString = buildString(256) {
      for ((_, value) in cacheInfoRecordMap) {
        val appliedTransformations = value.appliedTransformations
          .map { it.type }
          .joinToString(appliedTransformSeparator)

        append(value.url)
        append(separator)
        append(value.cachedFile.absolutePath)
        append(separator)
        append(value.lastAccessTime)
        append(separator)
        append("($appliedTransformations)")
        appendln()
      }
    }

    cacheInfoFile.writeText(outString)
  }

  /**
   * Private operations
   * */

  private fun debugPrint(msg: String) {
    if (showDebugLog) {
      println("thread: ${Thread.currentThread().name}, $msg")
    }
  }

  private fun getOldestCacheEntries(
    sizeAtLest: Long,
    cacheInfoRecordMap: HashMap<String, CacheInfoRecord>
  ): List<CacheInfoRecord> {
    val sortedCacheEntries = cacheInfoRecordMap.values
      .sortedBy { it.lastAccessTime }

    var accumulatedSize = 0L
    val oldest = mutableListOf<CacheInfoRecord>()

    for (entry in sortedCacheEntries) {
      if (accumulatedSize >= sizeAtLest) {
        break
      }

      accumulatedSize += entry.cachedFile.length()
      oldest += entry
    }

    return oldest
  }

  private fun calculateTotalCacheSize(cacheInfoRecordMap: HashMap<String, CacheInfoRecord>): Long {
    if (cacheInfoRecordMap.isEmpty()) {
      return 0L
    }

    return cacheInfoRecordMap.values
      .map { it.cachedFile.length() }
      .reduce { acc, len -> acc + len }
  }

  private fun removeCorruptedEntries(cacheInfoRecordMap: HashMap<String, CacheInfoRecord>) {
    val filesInCacheDirectory = getAllCachedFilesInCacheDir()
    val filesToDelete = mutableListOf<File>()

    for (file in filesInCacheDirectory) {
      val fileName = file.name

      for (cacheEntry in cacheInfoRecordMap.values) {
        if (fileName == cacheEntry.cachedFile.name) {
          break
        }

        cacheInfoRecordMap.remove(cacheEntry.url)
        filesToDelete.add(cacheEntry.cachedFile)
      }
    }

    filesToDelete.forEach {
      deleteFile(it)
    }

    innerUpdateCacheInfoFile(cacheInfoRecordMap)
  }

  private fun getAllCachedFilesInCacheDir(): Array<File> {
    return cacheDir
      .listFiles { _, fileName -> fileName != cacheInfoFile.name } ?: emptyArray()
  }

  private fun deleteFile(file: File) {
    if (!file.exists() || !file.isFile) {
      return
    }

    val path = file.toPath()
    Files.deleteIfExists(path)
  }

  private fun createCachedImageFile(key: String): File {
    val fileName = "${System.nanoTime()}_${key.hashCode().toUInt()}.cached"
    return File(cacheDir, fileName)
  }

  sealed class CacheOperation {
    class ReadCacheInfoFile(val result: CompletableDeferred<Unit>) : CacheOperation()

    class Store(val key: String,
                val cacheValue: CacheValue,
                val result: CompletableDeferred<Unit>) : CacheOperation()

    class Contains(val key: String,
                   val result: CompletableDeferred<Boolean>) : CacheOperation()

    class Get(val key: String,
              val result: CompletableDeferred<CacheValue?>) : CacheOperation()

    class Size(val result: CompletableDeferred<Int>) : CacheOperation()

    class Remove(val key: String,
                 val result: CompletableDeferred<Unit>) : CacheOperation()

    class GetOldest(val sizeAtLest: Long,
                    val result: CompletableDeferred<List<CacheInfoRecord>>) : CacheOperation()

    class EvictOldest(val result: CompletableDeferred<CacheValue?>) : CacheOperation()

    class Clear(val result: CompletableDeferred<Unit>) : CacheOperation()
  }
}