# Caching-Image-Loader
Image loader for JavaFX's ImageView.

Samples
---

First of all you need to create `CachingImageLoader`. 
Usually it should be stored in some singleton class.
Then you can download an image and render it in an `ImageView` like this:

```
imageLoader.newRequest()
  .load("https://i.imgur.com/e8h3Mbc.jpg")
  .transformers(TransformationBuilder().resize(400, 400))
  .into(imageView)
```

![Result](https://github.com/K1rakishou/Caching-Image-Loader/blob/master/art/default_result.jpg)

This will also store downloaded image onto the disk. 
By default it will create a subdirectory named `image-cache` in the folder where the binary file is located:
`File(System.getProperty("user.dir"), "\\image-cache"`)


Now lets apply some transformations:
- CenterCrop: will scale the image down so that one of the image's dimensions fits target dimensions and then crop out the center of the image
- CircleCrop: will draw the image inside a circle 

Now also store it in the cache with already applied transformations so we don't have to apply them every time we load this image from the cache:

```
imageLoader.newRequest()
  .load("https://i.imgur.com/vzsgL0n.jpg")
  .transformers(
    TransformationBuilder()
      .centerCrop(imageView)
      .circleCrop(
        CircleCropParametersBuilder()
          .backgroundColor(Color.RED)
          .stroke(10f, Color.GREEN)
      )
  )
  .saveStrategy(SaveStrategy.SaveTransformedImage)
  .into(imageView)
```
![Result](https://github.com/K1rakishou/Caching-Image-Loader/blob/master/art/circle_crop_result.jpg)

There is also an option to just retrieve a CompletableFuture holding the Image object without loading it into an ImageView:

```
val future: CompletableFuture<Image?> = imageLoader.newRequest()
  .load("https://i.imgur.com/vzsgL0n.jpg")
  .getAsync()
```
