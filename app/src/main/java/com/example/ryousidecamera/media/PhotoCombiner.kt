package com.example.ryousidecamera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object PhotoCombiner {

    suspend fun combine(
        context: Context,
        backBitmap: Bitmap,
        frontBitmap: Bitmap
    ): Uri = withContext(Dispatchers.Default) {
        val outWidth = backBitmap.width
        val outHeight = backBitmap.height

        val combined = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)

        // Draw back camera (full frame)
        canvas.drawBitmap(backBitmap, 0f, 0f, null)

        // Calculate PiP dimensions (top-right, 1/4 of width)
        val pipW = (outWidth * 0.28f).toInt()
        val pipH = (pipW.toFloat() / frontBitmap.width * frontBitmap.height).toInt()
        val margin = (outWidth * 0.025f).toInt()
        val pipLeft = outWidth - pipW - margin
        val pipTop = margin

        val pipScaled = Bitmap.createScaledBitmap(frontBitmap, pipW, pipH, true)

        // Draw white border around PiP
        val borderPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(
            RectF(
                pipLeft.toFloat() - 2,
                pipTop.toFloat() - 2,
                (pipLeft + pipW).toFloat() + 2,
                (pipTop + pipH).toFloat() + 2
            ),
            borderPaint
        )

        canvas.drawBitmap(pipScaled, pipLeft.toFloat(), pipTop.toFloat(), null)
        pipScaled.recycle()

        saveToGallery(context, combined)
    }

    private suspend fun saveToGallery(context: Context, bitmap: Bitmap): Uri =
        withContext(Dispatchers.IO) {
            val filename = "RyousideCamera_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RyousideCamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")

            resolver.openOutputStream(uri)!!.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            bitmap.recycle()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            uri
        }
}
