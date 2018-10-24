package transformers

import java.awt.image.BufferedImage
import java.awt.Image


class ResizeTransformer(
  private val newWidth: Double,
  private val newHeight: Double
) : ImageTransformer {

  override fun transform(inputImage: BufferedImage): BufferedImage {
    val width = newWidth.toInt()
    val height = newHeight.toInt()

    val tmp = inputImage.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val resizedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    val g2d = resizedImage.createGraphics()
    try {
      g2d.drawImage(tmp, 0, 0, null)
    } finally {
      g2d.dispose()
    }

    return resizedImage
  }
}