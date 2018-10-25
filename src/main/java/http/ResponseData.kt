package http

import java.io.InputStream

class ResponseData(
  val statusCode: Int,
  val contentType: String,
  val content: InputStream
)