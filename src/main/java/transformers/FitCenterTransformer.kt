package transformers

import java.awt.image.BufferedImage


class FitCenterTransformer(
  private val newWidth: Double,
  private val newHeight: Double
) : ImageTransformer {

  override val type = TransformationType.FitCenter

  override fun transform(inputImage: BufferedImage): BufferedImage {
    val sourceWidth = inputImage.width.toDouble()
    val sourceHeight = inputImage.height.toDouble()

    if (sourceWidth == newWidth && sourceHeight == newHeight) {
      return inputImage
    }

    val xScale = newWidth / sourceWidth
    val yScale = newHeight / sourceHeight

    val (newXScale, newYScale) = if (yScale > xScale) {
      ((1.0 / yScale) * xScale) to 1.0
    } else {
      1.0 to ((1.0 / xScale) * yScale)
    }

    val scaledWidth = newXScale * sourceWidth
    val scaledHeight = newYScale * sourceHeight

    val left = ((sourceWidth - scaledWidth) / 2).toInt()
    val top = ((sourceHeight - scaledHeight) / 2).toInt()
    val width = scaledWidth.toInt()
    val height = scaledHeight.toInt()

    val centeredImage = inputImage.getSubimage(left, top, width, height)
    return ResizeTransformer(newWidth, newHeight).transform(centeredImage)
  }
}