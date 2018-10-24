package transformers

import java.awt.image.BufferedImage

interface ImageTransformer {
  fun transform(inputImage: BufferedImage): BufferedImage
}