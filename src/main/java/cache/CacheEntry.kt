package cache

import transformations.TransformationType

class CacheEntry(
  val url: String,
  val fileName: String,
  val fileSize: Long,
  val addedOn: Long,
  val appliedTransformations: Array<TransformationType>
) {
  override fun toString(): String {
    return "[url: $url, fileName: $fileName]"
  }
}