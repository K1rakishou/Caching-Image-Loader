package builders

import transformations.parameters.CircleCropParams
import transformations.parameters.StrokeParams
import java.awt.Color

class CircleCropParametersBuilder {
  private var backgroundColorParam: Color? = null
  private var strokeWidthParam: Float? = null
  private var strokeColorParam: Color? = null

  fun getParameters(): CircleCropParams {
    val backGroundColor = if (backgroundColorParam != null) {
      backgroundColorParam!!
    } else {
      Color.BLACK
    }

    val strokeParams = when {
      strokeColorParam != null && strokeWidthParam != null -> {
        StrokeParams(strokeColorParam!!, strokeWidthParam!!)
      }
      else -> null
    }

    return CircleCropParams(backGroundColor, strokeParams)
  }

  fun backgroundColor(color: Color): CircleCropParametersBuilder {
    backgroundColorParam = color
    return this
  }

  fun stroke(strokeWidth: Float, strokeColor: Color): CircleCropParametersBuilder {
    strokeWidthParam = strokeWidth
    strokeColorParam = strokeColor
    return this
  }
}