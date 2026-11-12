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

    /** Valeur du réglage « thème » en mode automatique : le thème suit la date (fêtes, sinon saison). */
    const val AUTO = "auto"

    /** Fêtes dont la date se calcule (les fêtes lunaires — Nouvel An lunaire, Hanoukka — se choisissent à la main). */
    val AUTO_HOLIDAYS = listOf("jour_an", "st_valentin", "st_patrick", "paques", "fete_meres", "fete_peres", "st_jean", "canada",
        "independance_us", "action_grace", "halloween", "noel")

    /** Fêtes suivies par défaut selon le pays de la tablette. */
    fun defaultHolidays(country: String): Set<String> = when (country.uppercase()) {
        "CA" -> setOf("jour_an", "st_valentin", "st_patrick", "paques", "fete_meres", "fete_peres", "st_jean", "canada", "action_grace", "halloween", "noel")
        "US" -> setOf("jour_an", "st_valentin", "st_patrick", "paques", "fete_meres", "fete_peres", "independance_us", "action_grace", "halloween", "noel")
        else -> setOf("jour_an", "st_valentin", "paques", "halloween", "noel")
    }

    /** Jour du mois du [n]-ième [weekday] (Calendar.SUNDAY…) du mois [month] (1 à 12) de l'année [year]. */
    private fun nth(year: Int, month: Int, weekday: Int, n: Int): Int {
        val c = java.util.GregorianCalendar(year, month - 1, 1)
        val shift = (weekday - c.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
        return 1 + shift + (n - 1) * 7
    }

    /** Dimanche de Pâques (calendrier grégorien) : mois et jour. */
    private fun easter(year: Int): Pair<Int, Int> {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        return month to ((h + l - 7 * m + 114) % 31) + 1
    }

    /**
     * Thème du jour : une fête de [enabled] si c'en est une, sinon la saison.
     * Fenêtres : Noël du 1er au 26 décembre, Halloween du 25 au 31 octobre, Pâques du Vendredi saint au lundi,
     * jour de l'An le 31 décembre et le 1er janvier, Action de grâce la fin de semaine (Canada) ou le jeudi et le
     * lendemain (États-Unis, [country] = "US") ; les autres fêtes le jour même.
     */
    fun forDate(cal: java.util.Calendar, enabled: Set<String>, country: String): String {
        val y = cal.get(java.util.Calendar.YEAR); val m = cal.get(java.util.Calendar.MONTH) + 1; val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        fun on(key: String, hit: Boolean) = if (hit && key in enabled) key else null
        val (em, ed) = easter(y)
        val easterDay = java.util.GregorianCalendar(y, em - 1, ed).get(java.util.Calendar.DAY_OF_YEAR)
        val today = java.util.GregorianCalendar(y, m - 1, d).get(java.util.Calendar.DAY_OF_YEAR)
        val thanksgiving = if (country.uppercase() == "US") nth(y, 11, java.util.Calendar.THURSDAY, 4).let { m == 11 && d in it..it + 1 }
            else nth(y, 10, java.util.Calendar.MONDAY, 2).let { m == 10 && d in it - 2..it }
        return on("noel", m == 12 && d in 1..26)
            ?: on("jour_an", (m == 12 && d == 31) || (m == 1 && d == 1))
            ?: on("st_valentin", m == 2 && d == 14)
            ?: on("st_patrick", m == 3 && d == 17)
            ?: on("paques", today in easterDay - 2..easterDay + 1)
            ?: on("fete_meres", m == 5 && d == nth(y, 5, java.util.Calendar.SUNDAY, 2))
            ?: on("fete_peres", m == 6 && d == nth(y, 6, java.util.Calendar.SUNDAY, 3))
            ?: on("st_jean", m == 6 && d == 24)
            ?: on("canada", m == 7 && d == 1)
            ?: on("independance_us", m == 7 && d == 4)
            ?: on("action_grace", thanksgiving)
            ?: on("halloween", m == 10 && d in 25..31)
            ?: seasonFor(m)
    }

    /** Thème de saison pour un mois (1 à 12), dans l'hémisphère nord. */
    fun seasonFor(month: Int): String = when (month) { 12, 1, 2 -> "hiver"; 3, 4, 5 -> "printemps"; 6, 7, 8 -> "ete"; else -> "automne" }

    /** Fond de l'écran : le thème s'il y en a un, sinon le fond d'écran (dégradé ou photo personnelle). */
    fun background(ctx: Context, cfg: ConfigStore): Drawable {
        get(cfg.activeTheme)?.let { return ThemeDrawable(ctx, it) }
        if (PhotoWallpaper.isPhoto(cfg.wallpaper))
            PhotoWallpaper.drawable(ctx, cfg.wallpaper, Skin.photoVeil(cfg.photoVeilLevel), cfg.wallpaperRev)?.let { return it }
        return Wallpapers.skinned(cfg.wallpaper)
    }

    /** Titre de l'accueil : vœu de fête, ou salutation + motif de saison. */
    fun greeting(cfg: ConfigStore, hour: Int): String {
        val t = get(cfg.activeTheme) ?: return HomeText.greeting(hour)
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
