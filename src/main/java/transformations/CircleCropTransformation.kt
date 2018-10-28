package transformations

import transformations.parameters.CircleCropParams
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage


class CircleCropTransformation(
  private val parameters: CircleCropParams
) : ImageTransformation {

  override val type = TransformationType.CircleCrop

  override fun transform(inputImage: BufferedImage): BufferedImage {
    if (inputImage.width != inputImage.height) {
      throw RuntimeException("Width should be equal to height!")
    }

    val size = inputImage.width

    val circleBuffer = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = circleBuffer.createGraphics()

    try {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.paint = parameters.backgroundColor
      graphics.fillRect(0, 0, size, size)

      val arc = Arc2D.Float(0f, 0f, size.toFloat(), size.toFloat(), 0f, -360f, Arc2D.OPEN)
      graphics.clip = arc
      graphics.drawImage(inputImage, 0, 0, size, size, null)

      if (parameters.strokeParams != null) {
        graphics.color = parameters.strokeParams.strokeColor
        graphics.stroke = BasicStroke(parameters.strokeParams.strokeWidth)
        graphics.draw(arc)
      }
    } finally {
      graphics.dispose()
    }

    return circleBuffer
  }
}