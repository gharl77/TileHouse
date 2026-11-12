package com.homepanel.kiosk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File

/**
 * Photo personnelle comme fond d'écran (réglage « Ma photo »).
 *
 * La photo reçue (page web ou sélecteur de la tablette) est réduite à la taille de l'écran et gardée dans
 * le stockage privé de l'app. À l'affichage, elle est recadrée une fois pour toutes à la taille exacte de
 * l'écran, avec le voile de lisibilité de la skin déjà appliqué : dessiner le fond ne coûte alors qu'une
 * simple copie d'image (l'écran principal est rendu sans accélération matérielle).
 */
object PhotoWallpaper {
    /** Photo envoyée par l'utilisateur. */
    const val KEY = "photo"
    /** Photo du jour tirée de l'album Immich du cadre photo. */
    const val IMMICH = "immich"
    private const val MAX_BYTES = 25 * 1024 * 1024

    fun isPhoto(key: String) = key == KEY || key == IMMICH
    private fun file(ctx: Context, slot: String = KEY) = File(ctx.filesDir, if (slot == IMMICH) "wallpaper-immich.jpg" else "wallpaper.jpg")
    fun exists(ctx: Context, slot: String = KEY) = file(ctx, slot).length() > 0

    private var cached: Bitmap? = null
    private var cachedFor = ""

    /** Plus grand côté gardé sur la tablette : assez pour un écran de 1920 px, sans garder une photo de 12 Mpx. */
    private const val KEEP = 2048

    /**
     * Enregistre une nouvelle photo. Retourne null si tout va bien, sinon un message d'erreur lisible.
     * À appeler hors du fil principal (décodage et compression).
     */
    fun save(ctx: Context, bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return tr("Fichier vide ou trop gros")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return tr("Ce fichier n'est pas une image lisible (JPEG, PNG ou WebP).")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= KEEP) sample *= 2
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) { null } ?: return tr("Ce fichier n'est pas une image lisible (JPEG, PNG ou WebP).")
        val scale = minOf(1f, KEEP.toFloat() / maxOf(bmp.width, bmp.height))
        // Les photos de téléphone sont souvent enregistrées couchées, avec leur sens noté à part (EXIF) : on les redresse.
        val turn = Matrix().apply {
            setScale(scale, scale)
            when (orientation(bytes)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        val small = if (turn.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, turn, true)
        return try {
            val tmp = File(ctx.filesDir, "wallpaper.tmp")
            tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(file(ctx))) { tmp.copyTo(file(ctx), overwrite = true); tmp.delete() }
            synchronized(this) { cached = null; cachedFor = "" }
            null
        } catch (e: Exception) { tr("Impossible d'enregistrer la photo") + " (" + (e.message ?: "") + ")" }
        finally { if (small !== bmp) small.recycle(); bmp.recycle() }
    }

    private fun orientation(bytes: ByteArray): Int = try {
        android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Throwable) { android.media.ExifInterface.ORIENTATION_NORMAL }

    /** Enregistre une image déjà décodée (photo Immich du jour). */
    fun saveBitmap(ctx: Context, bmp: Bitmap, slot: String): Boolean = try {
        val tmp = File(ctx.filesDir, "wallpaper.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (!tmp.renameTo(file(ctx, slot))) { tmp.copyTo(file(ctx, slot), overwrite = true); tmp.delete() }
        synchronized(this) { cached = null; cachedFor = "" }
        true
    } catch (_: Exception) { false }

    fun delete(ctx: Context, slot: String = KEY) {
        file(ctx, slot).delete()
        synchronized(this) { cached = null; cachedFor = "" }
    }

    /** Photo recadrée à la taille de l'écran, voile [veil] appliqué ; null si la photo est absente ou illisible. */
    private fun screen(ctx: Context, slot: String, veil: Int, rev: Long): Bitmap? = synchronized(this) {
        val dm = ctx.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
        val key = "$slot|$rev|$veil|$w|$h"
        cached?.let { if (cachedFor == key) return it }
        val f = file(ctx, slot)
        if (f.length() <= 0) return null
        val src = try { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) } catch (_: Throwable) { null } ?: return null
        val out = try { Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565) } catch (_: Throwable) { src.recycle(); return null }
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply { setScale(s, s); postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f) }
        Canvas(out).apply {
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
            drawColor(veil)
        }
        src.recycle()
        cached = out; cachedFor = key
        out
    }

    fun drawable(ctx: Context, slot: String, veil: Int, rev: Long): Drawable? = screen(ctx, slot, veil, rev)?.let { BitmapDrawable(ctx.resources, it) }

    /** Couleur moyenne de la photo (accent des skins Pastel et Néon), ou null. */
    fun averageColor(ctx: Context, slot: String = KEY): Int? {
        val f = file(ctx, slot)
        if (f.length() <= 0) return null
        return try {
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 16 }) ?: return null
            val one = Bitmap.createScaledBitmap(b, 1, 1, true)
            val c = one.getPixel(0, 0)
            if (one !== b) one.recycle()
            b.recycle()
            c
        } catch (_: Throwable) { null }
    }

    /** Petite vignette JPEG en base64, pour l'aperçu de la page web ; null s'il n'y a pas de photo. */
    fun previewBase64(ctx: Context, slot: String = KEY): String? {
        val f = file(ctx, slot)
        if (f.length() <= 0) return null
        return try {
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
            val s = 360f / maxOf(b.width, b.height)
            val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (small !== b) small.recycle()
            b.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) { null }
    }
}
