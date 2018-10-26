package transformations.parameters

import java.awt.Color

class CircleCropParams(
  val backgroundColor: Color,
  val strokeParams: StrokeParams?
)

class StrokeParams(
  val strokeColor: Color,
  val strokeWidth: Float
)