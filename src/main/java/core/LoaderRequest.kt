package core

import javafx.scene.image.Image
import javafx.scene.image.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

sealed class LoaderRequest {
  class DownloadAsyncRequest(val future: CompletableFuture<Image?>) : LoaderRequest()
  class DownloadAndShowRequest(val imageView: WeakReference<ImageView>) : LoaderRequest()
}