package cache

import transformers.TransformationType

class CacheEntry(
  val url: String,
  val fileName: String,
  val fileSize: Long,
  val addedOn: Long,
  val appliedTransformations: Array<TransformationType>
)