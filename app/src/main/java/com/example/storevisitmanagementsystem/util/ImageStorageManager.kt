package com.example.storevisitmanagementsystem.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class ImageStorageManager(private val context: Context) {

    companion object {
        const val MAX_IMAGE_DIMENSION = 1024
        const val TARGET_QUALITY = 80
        const val TARGET_SIZE_BYTES = 300 * 1024 // 300 KB
        private val abandonedCaptureAge = TimeUnit.HOURS.toMillis(24)
        private val activeCapturePaths = ConcurrentHashMap.newKeySet<String>()

        fun protectCapture(file: File) {
            activeCapturePaths.add(file.absolutePath)
        }

        fun releaseCapture(file: File) {
            activeCapturePaths.remove(file.absolutePath)
        }
    }

    private fun getPhotoDirectory(userId: String): File {
        val dir = File(context.filesDir, "task_photos/$userId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Creates a temporary app-controlled `.tmp` file for camera capture.
     */
    fun createTempImageFile(userId: String): File {
        val photoDir = getPhotoDirectory(userId)
        return File.createTempFile(
            "temp_photo_${System.currentTimeMillis()}_",
            ".tmp",
            photoDir
        ).also(::protectCapture)
    }

    /**
     * Obtains a secure content URI for the temporary file via FileProvider.
     */
    fun getUriForFile(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Requirement 4: Off-main-thread image compression and downscaling.
     * Writes compressed JPEG to private storage and deletes the temporary `.tmp` file.
     */
    open suspend fun compressAndSavePhoto(tempFile: File, userId: String): File = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw IOException("Temporary photo file does not exist or is empty.")
        }

        val photoDir = getPhotoDirectory(userId)
        val photoId = "pho_${UUID.randomUUID().toString().take(12)}"
        val finalFile = File(photoDir, "$photoId.jpg")

        var bitmap = decodeSampledBitmap(tempFile.absolutePath, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
            ?: throw IOException("Failed to decode photo image.")

        // Correct orientation from EXIF if needed
        bitmap = rotateBitmapIfNeeded(tempFile.absolutePath, bitmap)

        try {
            var quality = TARGET_QUALITY
            do {
                FileOutputStream(finalFile).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                        throw IOException("Failed to encode photo.")
                    }
                    out.flush()
                }
                quality -= 10
            } while (finalFile.length() > TARGET_SIZE_BYTES && quality >= 50)
        } catch (e: Exception) {
            finalFile.delete()
            throw e
        } finally {
            bitmap.recycle()
        }

        // Clean up temporary capture file
        if (tempFile.exists()) {
            tempFile.delete()
        }

        return@withContext finalFile
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false

        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotateBitmapIfNeeded(path: String, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Requirement 4: Reconciles and cleans up temporary `.tmp` files from crashes.
     * Never deletes valid `.jpg` files that are referenced in Room.
     */
    suspend fun reconcileOrphanedTempFiles(userId: String) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - abandonedCaptureAge
        getPhotoDirectory(userId).listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp") && file.lastModified() < cutoff &&
                file.absolutePath !in activeCapturePaths
            ) {
                file.delete()
            }
        }
    }
}
