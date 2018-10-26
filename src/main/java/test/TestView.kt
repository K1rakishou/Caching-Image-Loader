package test

import builders.CircleCropParametersBuilder
import builders.TransformerBuilder
import core.CachingImageLoader
import core.SaveStrategy
import javafx.scene.image.ImageView
import tornadofx.*
import java.awt.Color
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
  launch<TestApp>(args)
}

internal class TestApp : App(TestView::class)

internal class TestView : View() {
  private val imageLoader = CachingImageLoader()
  private val counter = AtomicInteger(0)
  private val images = listOf(
    "https://i.imgur.com/cc2M7sK.png",
    "https://i.imgur.com/aCedFRM.jpg",
    "https://i.imgur.com/858QmKq.jpg",
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
          imageLoader.newRequest()
            .load(getImage())
            .transformers(
              TransformerBuilder()
                .fitCenter(imageView)
                .circleCrop(
                  CircleCropParametersBuilder()
                    .backgroundColor(Color.RED)
                    .stroke(10f, Color.GREEN)
                )
            )
            .saveStrategy(SaveStrategy.SaveTransformedImage)
            .into(imageView)
        }
      }
    }
    imageView = imageview {
      fitWidth = 600.0
      fitHeight = 600.0
    }

  }

}