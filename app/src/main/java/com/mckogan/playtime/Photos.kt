package com.mckogan.playtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Kid pictures. A chosen photo is cut to a square and shrunk to a small JPEG (about 10 KB),
 * so it fits in the family's shared settings and syncs to the other parent's phone.
 */
object Photos {

    private const val SIZE_PX = 192
    private const val QUALITY = 80
    private val cache = HashMap<String, Bitmap>()

    /** Reads the photo at [uri] and returns it as a small Base64 JPEG, or null if it can't be read. */
    fun fromUri(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        // First pass: only the size, to load a version that's not much bigger than needed.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE_PX) sample *= 2
        val full = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        // Center square, then scale down.
        val side = minOf(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, SIZE_PX, SIZE_PX, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    /** Decodes a stored photo (cached, since screens redraw often). */
    fun bitmap(photo: String): Bitmap? = cache.getOrPut(photo) {
        val bytes = runCatching { Base64.decode(photo, Base64.NO_WRAP) }.getOrNull() ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    }
}
