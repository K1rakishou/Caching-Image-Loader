import javafx.scene.image.ImageView
import javafx.scene.layout.Priority
import kotlinx.coroutines.launch
import tornadofx.*

fun main(args: Array<String>) {
  launch<TestApp>(args)
}

class TestApp : App(TestView::class)

class TestView : View() {
  private val imageLoader = CachingImageLoader()

  private lateinit var imageView: ImageView

  init {
    imageLoader.init()
  }

  override val root = vbox {
    prefWidth = 700.0
    prefHeight = 1280.0

    imageView = imageview {
      prefWidth = 600.0
      prefHeight - 1100.0
    }
    button("LOAD IMAGE") {
      action {
        launch {
          imageLoader.loadImageInto("https://i.imgur.com/s0FJbU6.png", imageView)
        }
      }
    }
  }

}