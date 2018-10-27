package cache

import transformations.TransformationType
import java.io.File

class CacheInfoRecord(
  val url: String,
  val cachedFile: File,
  val addedOn: Long,
  val appliedTransformations: Array<TransformationType>
) {

  override fun toString(): String {
    return "[url: $url, fileName: ${cachedFile.name}]"
  }
}