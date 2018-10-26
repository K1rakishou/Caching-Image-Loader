package transformations

import java.awt.image.BufferedImage

interface ImageTransformation {
  val type: TransformationType

  fun transform(inputImage: BufferedImage): BufferedImage
}