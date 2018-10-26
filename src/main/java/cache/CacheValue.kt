package cache

import transformations.TransformationType
import java.io.File

class CacheValue(
  val file: File,
  val appliedTransformations: Array<TransformationType>
)