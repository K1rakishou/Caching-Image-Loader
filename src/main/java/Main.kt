import javafx.scene.image.ImageView
import tornadofx.*
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
  launch<TestApp>(args)
}

class TestApp : App(TestView::class)

class TestView : View() {
  private val imageLoader = CachingImageLoader()
  private val counter = AtomicInteger(0)
  private val images = listOf(
    "https://i.imgur.com/aHeHrkH.jpg",
    "https://i.imgur.com/BrainlI.jpg",
    "https://i.imgur.com/s0FJbU6.png",
    "https://i.imgur.com/FNhvKzq.jpg"
  )

  private lateinit var imageView: ImageView

  private fun getImage(): String {
    return images[counter.getAndIncrement() % images.size]
  }

  override val root = vbox {
    prefWidth = 700.0
    maxWidth = 700.0
    prefHeight = 1280.0
    maxHeight = 1280.0

    useMaxWidth = true
    useMaxHeight = true

    hbox {
      button("LOAD IMAGE") {
        action {
          imageLoader.loadImageInto(getImage(), imageView)
        }
      }
    }
    imageView = imageview {
      maxWidth = 600.0
      maxHeight = 1100.0
    }

  }

}