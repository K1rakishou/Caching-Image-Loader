package core

import javafx.scene.image.Image
import kotlinx.coroutines.channels.SendChannel

sealed class LoaderRequest {
  class DownloadAsyncRequest(val channel: SendChannel<Image?>) : LoaderRequest()
}