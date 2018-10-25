package transformers

import java.awt.image.BufferedImage

interface ImageTransformer {
  val type: TransformationType

  fun transform(inputImage: BufferedImage): BufferedImage
}