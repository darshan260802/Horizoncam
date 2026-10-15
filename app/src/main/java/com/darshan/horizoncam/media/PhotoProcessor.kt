package com.darshan.horizoncam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import com.darshan.horizoncam.core.FrameGeometry
import com.darshan.horizoncam.core.FrameOrientation
import com.darshan.horizoncam.core.FramePlanner
import com.darshan.horizoncam.core.HorizonMath
import com.darshan.horizoncam.core.LockMode
import com.darshan.horizoncam.core.RenderConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Straightens a captured JPEG using the device orientation measured at the exact
 * exposure time, crops it to the selected frame and saves it to the gallery.
 */
object PhotoProcessor {
    private const val TAG = "PhotoProcessor"

    class Params(
        val lockMode: LockMode,
        val levelRange: Double,
        val orientation: FrameOrientation,
        val aspect: Double,
        /** Device "up" vector at exposure time (device coordinates). */
        val upX: Double,
        val upY: Double,
        /** Snapped device orientation at shutter time. */
        val betaDev: Int,
        val isFront: Boolean,
        val mirrorFront: Boolean,
        /** Camera sensor orientation; used to fix JPEGs delivered without the requested rotation. */
        val sensorOrientation: Int = 0
    )

    class Result(val uri: Uri?, val thumbnail: Bitmap?, val width: Int, val height: Int, val error: String?)

    fun process(context: Context, jpeg: ByteArray, p: Params): Result {
        val name = MediaSaver.timestampName("HC", "jpg")
        try {
            val exifIn = try { ExifInterface(ByteArrayInputStream(jpeg)) } catch (e: Exception) { null }
            val exifOrientation = exifIn?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL

            // Probe size to pick a safe sample size.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 26_000_000L) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                ?: return Result(null, null, 0, 0, "Could not decode photo")

            // 1) Bring the image into natural (device-upright, true-view) orientation.
            var natural = applyExifOrientation(raw, exifOrientation)
            if (natural !== raw) raw.recycle()
            // Some capture paths (notably vendor HDR / Night extensions) ignore JPEG_ORIENTATION and
            // deliver the sensor-oriented image. Sensors are landscape, so an upright phone must give
            // a portrait image; rotate the way JPEG_ORIENTATION would have.
            var fixedRotation = false
            if (p.sensorOrientation % 180 == 90 && natural.width > natural.height) {
                val m = Matrix().apply { postRotate(p.sensorOrientation.toFloat()) }
                val rotated = Bitmap.createBitmap(natural, 0, 0, natural.width, natural.height, m, true)
                if (rotated !== natural) natural.recycle()
                natural = rotated
                fixedRotation = true
            }
            val wn = natural.width.toDouble()
            val hn = natural.height.toDouble()

            // 2) Plan the straightening rotation + crop (pure logic, unit tested).
            val cfg = RenderConfig(
                lockMode = p.lockMode, levelRange = p.levelRange, orientation = p.orientation,
                aspect = p.aspect, mirrorFront = p.mirrorFront
            )
            val plan = FramePlanner.planPhoto(natural.width, natural.height, cfg, FrameGeometry.rollDeg(p.upX, p.upY), p.betaDev, p.isFront)
            val wo = plan.width
            val ho = plan.height
            val gamma = plan.gammaDeg
            val flip = plan.flip

            val identity = abs(HorizonMath.wrapDeg(gamma)) < 1e-6 && !flip && wo >= natural.width - 2 && ho >= natural.height - 2
            if (identity && exifOrientation == ExifInterface.ORIENTATION_NORMAL && !fixedRotation) {
                // Nothing to straighten or crop: keep the original JPEG untouched.
                var savedUri: Uri? = null
                val lock = Object()
                var done = false
                MediaSaver.saveJpeg(context, jpeg, null, name) { uri ->
                    synchronized(lock) { savedUri = uri; done = true; lock.notifyAll() }
                }
                synchronized(lock) { if (!done) lock.wait(5000) }
                val thumb = Bitmap.createScaledBitmap(natural, 240, max(1, (240 * hn / wn).toInt()), true)
                natural.recycle()
                return Result(savedUri, thumb, wn.toInt(), hn.toInt(), if (savedUri == null) "Save failed" else null)
            }

            // 3) Render: Canvas needs image -> output, the plan gives output -> image.
            val o2i = plan.outToImage
            val m = Matrix()
            m.setValues(floatArrayOf(o2i.a.toFloat(), o2i.b.toFloat(), o2i.tx.toFloat(), o2i.c.toFloat(), o2i.d.toFloat(), o2i.ty.toFloat(), 0f, 0f, 1f))
            val inv = Matrix()
            m.invert(inv)
            val out = Bitmap.createBitmap(wo, ho, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(natural, inv, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            natural.recycle()

            // 4) Encode + copy key EXIF tags.
            val tmp = File(context.cacheDir, "photo_tmp.jpg")
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            try {
                val exifOut = ExifInterface(tmp.absolutePath)
                if (exifIn != null) {
                    for (tag in COPY_TAGS) exifIn.getAttribute(tag)?.let { exifOut.setAttribute(tag, it) }
                }
                exifOut.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exifOut.setAttribute(ExifInterface.TAG_SOFTWARE, "HorizonCam")
                if (exifIn?.getAttribute(ExifInterface.TAG_MAKE) == null) exifOut.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                if (exifIn?.getAttribute(ExifInterface.TAG_MODEL) == null) exifOut.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                exifOut.saveAttributes()
            } catch (e: Exception) {
                Log.w(TAG, "EXIF copy failed", e)
            }
            val thumb = Bitmap.createScaledBitmap(out, 240, max(1, 240 * ho / wo), true)
            out.recycle()

            var savedUri: Uri? = null
            val lock = Object()
            var done = false
            MediaSaver.saveJpeg(context, null, tmp, name) { uri ->
                synchronized(lock) { savedUri = uri; done = true; lock.notifyAll() }
            }
            synchronized(lock) { if (!done) lock.wait(5000) }
            tmp.delete()
            return Result(savedUri, thumb, wo, ho, if (savedUri == null) "Save failed" else null)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM", oom)
            return Result(null, null, 0, 0, "Not enough memory to process the photo")
        } catch (e: Exception) {
            Log.e(TAG, "process failed", e)
            return Result(null, null, 0, 0, "Photo failed: ${e.message}")
        }
    }

    private fun applyExifOrientation(b: Bitmap, o: Int): Bitmap {
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> return b
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private val COPY_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL
    )
}
