package builders

import javafx.scene.image.ImageView
import transformations.CircleCropTransformation
import transformations.CenterCropTransformation
import transformations.ImageTransformation
import transformations.ResizeTransformation

class TransformationBuilder {
  private val transformations = mutableListOf<ImageTransformation>()

  fun getTransformers(): MutableList<ImageTransformation> = transformations

  fun centerCrop(width: Int, height: Int): TransformationBuilder {
    if (transformations.any { it is CenterCropTransformation }) {
      throw IllegalStateException("CenterCropTransformation already applied!")
    }

    transformations.add(CenterCropTransformation(width.toDouble(), height.toDouble()) as ImageTransformation)
    return this
  }

  fun centerCrop(view: ImageView): TransformationBuilder {
    if (transformations.any { it is CenterCropTransformation }) {
      throw IllegalStateException("CenterCropTransformation already applied!")
    }

    transformations.add(CenterCropTransformation(view.fitWidth, view.fitHeight) as ImageTransformation)
    return this
  }

  fun circleCrop(circleCropParamsBuilder: CircleCropParametersBuilder): TransformationBuilder {
    if (transformations.any { it is CircleCropTransformation }) {
      throw IllegalStateException("CircleCropTransformation already applied!")
    }

    transformations.add(CircleCropTransformation(circleCropParamsBuilder.getParameters()) as ImageTransformation)
    return this
  }

  fun resize(newWidth: Int, newHeight: Int): TransformationBuilder {
    if (transformations.any { it is ResizeTransformation }) {
      throw IllegalStateException("ResizeTransformation already applied!")
    }

    transformations.add(ResizeTransformation(newWidth.toDouble(), newHeight.toDouble()) as ImageTransformation)
    return this
  }

  fun resize(newWidth: Double, newHeight: Double): TransformationBuilder {
    if (transformations.any { it is ResizeTransformation }) {
      throw IllegalStateException("ResizeTransformation already applied!")
    }

    transformations.add(ResizeTransformation(newWidth, newHeight) as ImageTransformation)
    return this
  }

  fun noTransformations(): TransformationBuilder {
    return this
  }
}
