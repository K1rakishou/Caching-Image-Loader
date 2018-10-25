package http

interface HttpClientFacade {
  fun fetchImage(url: String, response: (ResponseData?) -> Unit)
  fun close()
}