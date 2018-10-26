package http

import java.util.concurrent.CompletableFuture

interface HttpClientFacade {
  fun fetchImage(url: String): CompletableFuture<ResponseData?>
  fun close()
}