package transformations

enum class TransformationType(val type: Int) {
  CenterCrop(0),
  Resize(1),
  CircleCrop(2);

  companion object {
    fun fromInt(value: Int): TransformationType? {
      return TransformationType.values().firstOrNull { it.type == value }
    }
  }
}