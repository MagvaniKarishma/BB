package com.brokerbuddy.ui.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.brokerbuddy.R
import com.brokerbuddy.data.ApiClient
import com.brokerbuddy.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Listing photos. They are private to the brokerage, so they're fetched through the
 * signed-in API client (not a public URL), then cached: decoded bitmaps in memory, the
 * downloaded file on disk (a photo id never changes content).
 */
object PropertyPhotos {
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun file(context: Context, photoId: String) =
        File(File(context.cacheDir, "photos").apply { mkdirs() }, photoId.replace(Regex("[^A-Za-z0-9_-]"), ""))

    suspend fun load(context: Context, api: ApiClient, propertyId: String, photoId: String, maxPx: Int): Bitmap? {
        val key = "$photoId@$maxPx"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(context, photoId)
            if (!f.exists()) {
                val body = api.call { propertyPhoto(propertyId, photoId) }.getOrNull() ?: return@withContext null
                val tmp = File(f.parentFile, "${f.name}.part")
                body.use { b -> tmp.outputStream().use { b.byteStream().copyTo(it) } }
                tmp.renameTo(f)
            }
            decode(f, maxPx)?.also { memory.put(key, it) }
        }
    }

    private fun decode(f: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx / 2) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * A picked photo resized to at most [maxSide] px and re-encoded as JPEG, so uploads stay
     * well under the server's 5 MB limit and phone camera metadata (location) isn't sent.
     */
    suspend fun prepareUpload(context: Context, uri: Uri, maxSide: Int = 1600): ByteArray? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null
        // Camera photos are often stored sideways with an EXIF rotation tag; JPEG re-encoding drops
        // the tag, so apply the rotation to the pixels.
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees)
        }
        val bitmap = if (scale < 1f || degrees != 0f) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) else decoded
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }
}

/** A listing photo, or the placeholder artwork while loading / when there are no photos. */
@Composable
fun PropertyPhoto(
    propertyId: String,
    photoId: String?,
    modifier: Modifier = Modifier,
    maxPx: Int = 800,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val api = appContainer().api
    var image by remember(photoId, maxPx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoId, maxPx) {
        if (photoId != null) image = PropertyPhotos.load(context, api, propertyId, photoId, maxPx)?.asImageBitmap()
    }
    Box(modifier) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Image(painterResource(R.drawable.property_placeholder), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
