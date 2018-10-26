package core

import javafx.scene.image.Image
import javafx.scene.image.ImageView
import kotlinx.coroutines.CompletableDeferred
import java.lang.ref.WeakReference

sealed class LoaderRequest {
  class DownloadAsyncRequest(val future: CompletableDeferred<Image?>) : LoaderRequest()
  class DownloadAndShowRequest(val imageView: WeakReference<ImageView>) : LoaderRequest()
}