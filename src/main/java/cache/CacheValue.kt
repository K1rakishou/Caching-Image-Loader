package cache

import transformations.TransformationType
import java.io.File

class CacheValue(
  val file: File,
  val appliedTransformations: Array<TransformationType>
) {

  override fun equals(other: Any?): Boolean {
    if (other == null) {
      return false
    }

    if (other === this) {
      return true
    }

    if (other::class.java != this.javaClass) {
      return false
    }

    other as CacheValue

    return file.absolutePath == other.file.absolutePath
  }

  override fun hashCode(): Int {
    return file.absolutePath.hashCode()
  }

  override fun toString(): String {
    return "[filePath: ${file.absolutePath}]"
  }
}