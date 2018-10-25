package cache

import transformers.TransformationType
import java.io.File

class CacheValue(
  val file: File,
  val appliedTransformations: Array<TransformationType>
)