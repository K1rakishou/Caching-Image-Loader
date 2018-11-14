# Caching-Image-Loader
[![](https://jitpack.io/v/K1rakishou/Caching-Image-Loader.svg)](https://jitpack.io/#K1rakishou/Caching-Image-Loader)

Image loader for JavaFX's ImageView.

Samples
---

First of all you need to create `CachingImageLoader`:

```
val imageLoader = CachingImageLoader()
```
You can specify the maximum disk cache size, directory where images will be stored, 
debug logs printing and a custom httpClient to download images from the network.

Usually it should be stored in some singleton class.
Then you can download an image and render it in an `ImageView` like this:

```
imageLoader.newRequest()
    .load("https://i.imgur.com/e8h3Mbc.jpg")
    .transformations(TransformationBuilder().noTransformations())
    .saveStrategy(SaveStrategy.SaveOriginalImage)
    .into(imageView)
```

![Result](https://github.com/K1rakishou/Caching-Image-Loader/blob/master/art/default_result.jpg)

This will also store downloaded image onto the disk. 
By default it will create a subdirectory named `image-cache` in the folder 
where the binary file is located:
`File(System.getProperty("user.dir"), "\\image-cache")`


Now lets apply some transformations:
- CenterCrop: will scale the image down so that one of the image's dimensions 
fits target dimensions and then crop out the center of the image
- Resize: will resize image
- CircleCrop: will draw the image inside a circle 

(Note: transformations will be applied in the same order they were declared in the building stage)

Instead of saving the original image lets store an image with applied transformations 
so we don't have to apply them every time we load this image from the cache:

```
imageLoader.newRequest()
    .load(getImage())
    .transformations(
      TransformationBuilder()
        .centerCrop(imageView)
        .resize(400, 400)
        .circleCrop(
          CircleCropParametersBuilder()
            .backgroundColor(Color(0f, 0f, 0f, 0f))
            .stroke(10f, Color.LIGHT_GRAY)
        )
    )
    .saveStrategy(SaveStrategy.SaveTransformedImage)
    .into(imageView)
```
![Result](https://github.com/K1rakishou/Caching-Image-Loader/blob/master/art/circle_crop_result.png)

- SaveStrategy.SaveOriginalImage: will save the original image. Transformations will be applied to the image
every time it's being loaded from the cache 
- SaveStrategy.SaveTransformedImage: will save image with applied transformations. Transformations 
will be applied only once. 

There is also an option to just retrieve a CompletableFuture holding the Image 
object without loading it into an ImageView:

```
val future: CompletableFuture<Image?> = imageLoader.newRequest()
  .load("https://i.imgur.com/vzsgL0n.jpg")
  .transformations(TransformationBuilder().noTransformations())
  .saveStrategy(SaveStrategy.SaveOriginalImage)
  .getAsync()
```