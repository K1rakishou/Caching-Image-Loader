package transformers

import java.awt.image.BufferedImage
import java.awt.Image


class ResizeTransformer(
  private val newWidth: Double,
  private val newHeight: Double
) : ImageTransformer {

  override val type = TransformationType.Resize

  override fun transform(inputImage: BufferedImage): BufferedImage {
    val width = newWidth.toInt()
    val height = newHeight.toInt()

    val tmp = inputImage.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val resizedImage = BufferedImage(width, height, inputImage.type)

    val g2d = resizedImage.createGraphics()
    try {
      g2d.drawImage(tmp, 0, 0, null)
    } finally {
      g2d.dispose()
    }

    return resizedImage
  }
}