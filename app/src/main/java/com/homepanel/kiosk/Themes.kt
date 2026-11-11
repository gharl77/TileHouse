package com.homepanel.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import java.util.Random

/**
 * Thèmes de saison et de fête, activés manuellement.
 * Un thème = un dégradé de fond + quelques motifs fixes et discrets + une salutation.
 * Rien n'est animé : aucun coût en continu pour la tablette.
 */
object Themes {
    class Theme(
        val key: String,
        private val frName: String,
        private val frGroup: String,   // "Saisons" ou "Fêtes"
        val colors: IntArray,
        val decor: List<String>,       // motifs dispersés sur le fond
        private val frWish: String? = null, // remplace la salutation sur l'accueil (fêtes)
    ) {
        val name get() = tr(frName)
        val group get() = tr(frGroup)
        val wish get() = frWish?.let { tr(it) }
        val emoji get() = decor.first()
    }

    private fun c(vararg hex: Long) = hex.map { it.toInt() }.toIntArray()

    val all = listOf(
        Theme("printemps", "Printemps", "Saisons", c(0xFF2E5E4E, 0xFF1F3D3A, 0xFF0F1E1D), listOf("🌷", "🌸", "🌱", "🐝")),
        Theme("ete", "Été", "Saisons", c(0xFF0E5A8A, 0xFF0B3B5E, 0xFF06202F), listOf("☀️", "🌴", "🍉", "🏖️")),
        Theme("automne", "Automne", "Saisons", c(0xFF7A3B12, 0xFF4A2410, 0xFF1E0F08), listOf("🍁", "🍂", "🍄", "🌰")),
        Theme("hiver", "Hiver", "Saisons", c(0xFF3A5878, 0xFF1E2F44, 0xFF0C1420), listOf("❄️", "⛄", "🌨️", "❄️")),
        Theme("jour_an", "Jour de l'An", "Fêtes", c(0xFF2B2B5A, 0xFF15153A, 0xFF08081A), listOf("🎆", "🥂", "🎉", "✨"), "Bonne année"),
        Theme("an_lunaire", "Nouvel An lunaire", "Fêtes", c(0xFF8A1A1A, 0xFF5A0F0F, 0xFF250606), listOf("🏮", "🐉", "🎊", "🎆"), "Bonne année lunaire"),
        Theme("st_valentin", "Saint-Valentin", "Fêtes", c(0xFF7A1F3D, 0xFF4A1226, 0xFF1E0710), listOf("❤️", "💕", "🌹", "💌"), "Joyeuse Saint-Valentin"),
        Theme("st_patrick", "Saint-Patrick", "Fêtes", c(0xFF1E6B34, 0xFF124222, 0xFF071A0E), listOf("☘️", "🍀", "🌈", "🎩"), "Joyeuse Saint-Patrick"),
        Theme("paques", "Pâques", "Fêtes", c(0xFF5B4A8A, 0xFF3A2F5E, 0xFF1A1530), listOf("🐣", "🥚", "🐰", "🌷"), "Joyeuses Pâques"),
        Theme("fete_meres", "Fête des mères", "Fêtes", c(0xFF7A3A6A, 0xFF4A2342, 0xFF1E0E1B), listOf("💐", "🌸", "💖", "🌷"), "Bonne fête des mères"),
        Theme("fete_peres", "Fête des pères", "Fêtes", c(0xFF2D4A6B, 0xFF1C3048, 0xFF0B1420), listOf("👔", "🛠️", "🏆", "💙"), "Bonne fête des pères"),
        Theme("st_jean", "Fête nationale du Québec", "Fêtes", c(0xFF0B3D91, 0xFF08285E, 0xFF04132E), listOf("⚜️", "🎆", "🔥", "⚜️"), "Bonne Fête nationale"),
        Theme("canada", "Fête du Canada", "Fêtes", c(0xFF8A1414, 0xFF5A0D0D, 0xFF240505), listOf("🍁", "🎆", "🇨🇦", "🍁"), "Bonne fête du Canada"),
        Theme("independance_us", "Fête de l'Indépendance (É.-U.)", "Fêtes", c(0xFF1F2F6B, 0xFF5A1420, 0xFF0B0F24), listOf("🇺🇸", "🎆", "⭐", "🎇"), "Joyeux 4 juillet"),
        Theme("action_grace", "Action de grâce", "Fêtes", c(0xFF6B4A1E, 0xFF3E2B12, 0xFF1A1208), listOf("🦃", "🥧", "🍂", "🌽"), "Joyeuse Action de grâce"),
        Theme("halloween", "Halloween", "Fêtes", c(0xFF4A2A0A, 0xFF2A1405, 0xFF0D0602), listOf("🎃", "👻", "🦇", "🕸️", "🍬"), "Joyeuse Halloween"),
        Theme("hanoukka", "Hanoukka", "Fêtes", c(0xFF1A3A7A, 0xFF10254E, 0xFF060F22), listOf("🕎", "✨", "🕯️", "⭐"), "Joyeuse Hanoukka"),
        Theme("noel", "Noël", "Fêtes", c(0xFF145A32, 0xFF0D3B21, 0xFF05190E), listOf("🎄", "🎁", "⭐", "❄️", "🔔"), "Joyeux Noël"),
    )

    fun get(key: String): Theme? = all.firstOrNull { it.key == key }

    /** Fond de l'écran : thème actif, sinon le fond d'écran choisi. */
    fun background(ctx: Context, cfg: ConfigStore): Drawable =
        get(cfg.theme)?.let { ThemeDrawable(ctx, it) } ?: Wallpapers.skinned(cfg.wallpaper)

    /** Titre de l'accueil : vœu de fête, ou salutation + motif de saison. */
    fun greeting(cfg: ConfigStore, hour: Int): String {
        val t = get(cfg.theme) ?: return HomeText.greeting(hour)
        return (t.wish ?: HomeText.greeting(hour)) + " " + t.emoji
    }
}

/** Dégradé + motifs semi-transparents, placés une fois pour toutes (pas d'animation). */
private class ThemeDrawable(ctx: Context, private val theme: Themes.Theme) : Drawable() {
    private val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, Skin.bgColors(theme.colors))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = Skin.decorAlpha }
    private val density = ctx.resources.displayMetrics.density
    private class Item(val fx: Float, val fy: Float, val size: Float, val text: String)
    private val items: List<Item>

    init {
        // Positions pseudo-aléatoires mais stables pour un même thème ; grille 4×4 jitterée
        // pour bien répartir les motifs sans qu'ils se chevauchent.
        val rnd = Random(theme.key.hashCode().toLong())
        val list = ArrayList<Item>()
        for (gy in 0 until 4) for (gx in 0 until 4) {
            if (rnd.nextFloat() < 0.25f) continue
            list.add(Item(
                (gx + 0.15f + rnd.nextFloat() * 0.7f) / 4f,
                (gy + 0.15f + rnd.nextFloat() * 0.7f) / 4f,
                (34 + rnd.nextInt(40)) * density,
                theme.decor[rnd.nextInt(theme.decor.size)],
            ))
        }
        items = list
    }

    override fun onBoundsChange(b: android.graphics.Rect) { gradient.bounds = b }

    override fun draw(canvas: Canvas) {
        gradient.draw(canvas)
        val b = bounds
        for (it in items) {
            paint.textSize = it.size
            canvas.drawText(it.text, b.left + it.fx * b.width(), b.top + it.fy * b.height(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
