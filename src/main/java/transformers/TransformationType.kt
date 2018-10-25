package transformers

enum class TransformationType(val type: Int) {
  FitCenter(0),
  Resize(1);

  companion object {
    fun fromInt(value: Int): TransformationType? {
      return TransformationType.values().firstOrNull { it.type == value }
    }
  }
}