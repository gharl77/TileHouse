package com.homepanel.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/**
 * Skins : façons d'afficher l'app (formes, surfaces, couleurs du texte, polices, tuiles, pastilles, tailles).
 * Le thème (fond, motifs, salutation) reste celui choisi ; la skin décide seulement du dessin.
 * Les écrans de réglages de la tablette ne suivent pas la skin.
 *
 * Chaque skin est une [SkinDef] : une seule classe à écrire pour en ajouter une. Les écrans ne connaissent
 * que la façade [Skin] (ex. `Skin.ink`, `Skin.card(…)`, `Skin.tile(…)`) et ne contiennent aucun cas par skin.
 */

/** Apparence d'une tuile d'appareil dans un état donné (lue par [TileView], qui ne fait que dessiner). */
class TileLook(
    val fill: Int,                 // fond (0 = aucun)
    val stroke: Int = 0,           // contour (0 = aucun)
    val strokeDp: Float = 1f,
    val underline: Int = 0,        // ligne fine sous la tuile, à la place d'un fond (0 = aucune)
    val glow: Int = 0,             // lueur intérieure (0 = aucune)
    val iconCircle: Int,           // pastille ronde derrière l'icône (0 = aucune)
    val iconTint: Int,
    val iconScale: Float = 0.56f,  // taille de l'icône par rapport à sa pastille
    val name: Int,
    val status: Int,
    val nameFace: Typeface? = null,
    val dot: Int = 0,              // point à droite quand l'appareil est allumé (0 = aucun)
)

/** Définition d'une skin. Les valeurs par défaut sont celles du style HomeKit. */
open class SkinDef(val key: String, private val frName: String) {
    val name get() = tr(frName)
    protected val tint get() = Skin.tint
    protected fun a(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    protected fun rr(color: Int, radius: Float, ctx: Context? = null, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (ctx != null && stroke != 0) setStroke(ctx.dp(1), stroke)
    }

    /** Pastel et Néon : l'accent vient du thème ; [tintFallback] sert quand le fond est gris. */
    open val usesTint = false
    open val tintSaturation = 0.45f
    open val tintFallback = 0xFFFFB300.toInt()

    // ---- tailles
    open val textScale = 1f
    open val tileMinW = 210
    open val tileH = 74
    open val tileGap = 12
    open val iconD = 38
    open val tilePad = 14
    open val nameSize = 15f
    open val statusSize = 13f
    open val tileRadius = 18f
    /** Coins des vignettes de caméra et du grand curseur. */
    open val mediaRadius = 18f
    open val sliderRadius = 30f

    /** Nombre de rangées de pastilles dans l'en-tête ; au-delà, une pastille « +N » ouvre la liste complète. */
    open val chipRows = Int.MAX_VALUE
    /** Tuiles étroites : statuts raccourcis (ex. « 20,5° → 21° »). */
    open val shortStatus = false

    // ---- couleurs
    open val ink = Color.WHITE
    /** Transparence réellement utilisée pour un texte secondaire demandé à [alpha]. */
    open fun inkAlpha(alpha: Int) = alpha
    open val mute = 0xFF8E95A3.toInt()
    open val lineAlpha = 0x1A
    open val soft get() = GLASS_LIGHT
    open val accent get() = 0xFFFFB300.toInt()
    open val panel get() = 0xF51C222D.toInt()
    open val panelStroke get() = 0x26FFFFFF
    open val primary get() = WHITE_TILE
    open val onPrimary = 0xFF1C1C1E.toInt()
    open val sliderTrack get() = 0x66808088
    /** Niveau du grand curseur (lumière, volet, volume). */
    open val sliderFill get() = 0x66FFFFFF

    // ---- polices
    open val bold: Typeface get() = Typeface.create("sans-serif", Typeface.BOLD)
    open val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** Grands chiffres (heure, températures). */
    open val light: Typeface get() = Typeface.create("sans-serif-light", Typeface.NORMAL)
    /** Heure de l'écran de veille. */
    open val clockFace: Typeface get() = Typeface.create("sans-serif-thin", Typeface.NORMAL)

    // ---- surfaces
    open fun card(ctx: Context, radius: Float): Drawable = roundRect(GLASS, radius)
    open fun panelRadius(radius: Float) = radius
    open fun softRect(ctx: Context, radius: Float): Drawable = roundRect(soft, radius)
    /** Pastille de l'en-tête selon son niveau (0 neutre, 1 attention, 2 alerte) : fond et couleur du texte. */
    open fun chip(ctx: Context, level: Int): Pair<Drawable, Int> =
        roundRect(when (level) { 2 -> 0xE6FF3B30.toInt(); 1 -> 0xD9FF9F0A.toInt(); else -> GLASS_LIGHT }, ctx.dpf(40)) to Color.WHITE
    /** Pastille personnalisée d'une couleur choisie : fond plein, texte clair ou sombre selon la couleur. */
    open fun chipColored(ctx: Context, color: Int): Pair<Drawable, Int> = rr(a(color, 0xE6), ctx.dpf(40)) to HomeExtras.textOn(color)
    /** Pilule de choix (modes du thermostat, boutons de l'écran vide). */
    open fun pill(ctx: Context, selected: Boolean): Pair<Drawable, Int> =
        if (selected) roundRect(WHITE_TILE, ctx.dpf(40)) to onPrimary else roundRect(GLASS_LIGHT, ctx.dpf(40)) to Color.WHITE
    open fun tile(active: Boolean, accent: Int): TileLook =
        if (active) TileLook(fill = WHITE_TILE, iconCircle = accent, iconTint = Color.WHITE, name = 0xFF1C1C1E.toInt(), status = 0xFF6E6E73.toInt())
        else TileLook(fill = GLASS, iconCircle = 0x2EFFFFFF, iconTint = 0xE6FFFFFF.toInt(), name = Color.WHITE, status = 0xA6FFFFFF.toInt())

    /** Fenêtres de contrôle : posées sur une feuille (vrai) ou flottant sur le voile sombre (faux). */
    open val dialogOnSheet = false
    open val dialogDim = 0.78f

    // ---- fond
    /** Voile posé sur une photo personnelle pour que le texte reste lisible (sombre, ou clair pour Papier). */
    open val photoVeil = 0x73000000
    open fun bgColors(colors: IntArray) = colors
    /** Transparence des motifs du thème sur le fond. */
    open val decorAlpha = 46
}

// ------------------------------------------------------------ les skins

/** Tuiles en verre, allumé = tuile blanche (style d'origine). */
private object HomeKitSkin : SkinDef("homekit", "HomeKit")

/** Comme HomeKit, en plus petit : plus d'appareils par ligne. */
private object CompactSkin : SkinDef("compact", "Compact") {
    override val textScale = 0.9f
    override val tileMinW = 160
    override val tileH = 58
    override val tileGap = 8
    override val iconD = 30
    override val tilePad = 10
    override val nameSize = 13.5f
    override val statusSize = 11.5f
    override val tileRadius = 14f
    override val shortStatus = true
}

/** Comme HomeKit, en très gros et très contrasté : 3 tuiles par ligne. */
private object ConfortSkin : SkinDef("confort", "Grand confort") {
    private const val DARK = 0xB3141418.toInt()
    override val textScale = 1.25f
    override val tileMinW = 300
    override val tileH = 104
    override val tileGap = 16
    override val iconD = 54
    override val tilePad = 20
    override val nameSize = 21f
    override val statusSize = 17f
    override val chipRows = 1
    override fun inkAlpha(alpha: Int) = if (alpha >= 0x80) maxOf(alpha, 0xF0) else alpha
    override val mute = 0xFFC9CED8.toInt()
    override val light: Typeface get() = Typeface.create("sans-serif", Typeface.NORMAL)
    override val photoVeil = 0x80000000.toInt()
    override fun card(ctx: Context, radius: Float): Drawable = rr(DARK, radius)
    override fun chip(ctx: Context, level: Int): Pair<Drawable, Int> =
        if (level == 0) roundRect(0x66FFFFFF, ctx.dpf(40)) to Color.WHITE else super.chip(ctx, level)
    override fun tile(active: Boolean, accent: Int): TileLook =
        if (active) TileLook(fill = WHITE_TILE, iconCircle = accent, iconTint = Color.WHITE, name = 0xFF1C1C1E.toInt(), status = 0xFF3A3A3F.toInt())
        else TileLook(fill = DARK, iconCircle = 0x2EFFFFFF, iconTint = Color.WHITE, name = Color.WHITE, status = 0xE6FFFFFF.toInt())
}

/** Sans tuiles pleines : lignes fines, typographie légère, allumé = icône et point de couleur. */
private object EpureSkin : SkinDef("epure", "Épuré") {
    override val nameSize = 16f
    /** Pas de tuiles pleines : la photo doit être nettement assombrie. */
    override val photoVeil = 0x99000000.toInt()
    override val mediaRadius = 10f
    override val sliderRadius = 18f
    override val soft get() = 0x14FFFFFF
    override val panel get() = 0xF50B0E13.toInt()
    override val panelStroke get() = 0x33FFFFFF
    override val bold: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    override val medium: Typeface get() = Typeface.create("sans-serif", Typeface.NORMAL)
    override val light: Typeface get() = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    override fun card(ctx: Context, radius: Float): Drawable = rr(0x0DFFFFFF, radius * 0.5f, ctx, 0x2EFFFFFF)
    override fun panelRadius(radius: Float) = radius * 0.55f
    override fun softRect(ctx: Context, radius: Float): Drawable = rr(0x0AFFFFFF, radius, ctx, 0x40FFFFFF)
    override fun chip(ctx: Context, level: Int): Pair<Drawable, Int> {
        val c = when (level) { 2 -> 0xFFFF6B5E.toInt(); 1 -> 0xFFFFB340.toInt(); else -> 0xE6FFFFFF.toInt() }
        return rr(0, ctx.dpf(40), ctx, a(c, 0x66)) to c
    }
    override fun chipColored(ctx: Context, color: Int): Pair<Drawable, Int> = rr(0, ctx.dpf(40), ctx, a(color, 0x99)) to color
    override fun pill(ctx: Context, selected: Boolean): Pair<Drawable, Int> =
        if (selected) rr(Color.WHITE, ctx.dpf(40)) to onPrimary else rr(0, ctx.dpf(40), ctx, 0x59FFFFFF) to Color.WHITE
    override fun tile(active: Boolean, accent: Int): TileLook =
        if (active) TileLook(fill = 0, underline = 0x29FFFFFF, iconCircle = 0, iconTint = accent, iconScale = 0.62f, name = Color.WHITE,
            status = a(accent, 0xE6), nameFace = Typeface.create("sans-serif-medium", Typeface.NORMAL), dot = accent)
        else TileLook(fill = 0, underline = 0x29FFFFFF, iconCircle = 0, iconTint = 0x99FFFFFF.toInt(), iconScale = 0.62f, name = 0xE6FFFFFF.toInt(),
            status = 0x8CFFFFFF.toInt(), nameFace = Typeface.create("sans-serif-light", Typeface.NORMAL))
}

/** Papier chaud et clair teinté par le thème, cartes blanches bordées, titres à empattements. */
private object PapierSkin : SkinDef("papier", "Papier") {
    private const val INK = 0xFF33281F.toInt()
    private const val SHEET = 0xFFFFFCF5.toInt()
    private const val EDGE = 0x2433281F
    override val tileRadius = 10f
    override val mediaRadius = 10f
    override val sliderRadius = 18f
    override val ink = INK
    override val mute = 0xFF8A7765.toInt()
    override val lineAlpha = 0x24
    override val soft get() = 0x1A33281F
    override val accent get() = 0xFFC8742E.toInt()
    override val panel get() = 0xFAFBF6EC.toInt()
    override val panelStroke get() = EDGE
    override val primary get() = INK
    override val onPrimary = 0xFFFBF6EC.toInt()
    override val sliderTrack get() = 0x2633281F
    override val sliderFill get() = a(accent, 0xE6)
    override val bold: Typeface get() = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    override val light: Typeface get() = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    override val clockFace: Typeface get() = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    override fun card(ctx: Context, radius: Float): Drawable = rr(SHEET, radius * 0.55f, ctx, EDGE)
    override fun panelRadius(radius: Float) = radius * 0.55f
    override fun chip(ctx: Context, level: Int): Pair<Drawable, Int> = when (level) {
        2 -> rr(0xFFC4452F.toInt(), ctx.dpf(8)) to Color.WHITE
        1 -> rr(0xFFF6D9A6.toInt(), ctx.dpf(8), ctx, 0x40C8742E) to INK
        else -> rr(SHEET, ctx.dpf(8), ctx, EDGE) to INK
    }
    override fun chipColored(ctx: Context, color: Int): Pair<Drawable, Int> = rr(color, ctx.dpf(8)) to HomeExtras.textOn(color)
    override fun pill(ctx: Context, selected: Boolean): Pair<Drawable, Int> =
        if (selected) rr(INK, ctx.dpf(10)) to onPrimary else rr(soft, ctx.dpf(10)) to INK
    override fun tile(active: Boolean, accent: Int): TileLook =
        if (active) TileLook(fill = 0xFFF8E3BC.toInt(), stroke = 0x59C8742E, iconCircle = accent, iconTint = Color.WHITE, name = INK, status = 0xFF8A5A2B.toInt())
        else TileLook(fill = SHEET, stroke = EDGE, iconCircle = 0x1433281F, iconTint = 0xCC33281F.toInt(), name = INK, status = 0xFF8A7765.toInt())
    override val dialogOnSheet = true
    override val dialogDim = 0.45f
    /** Voile de papier clair : la photo reste visible en filigrane sous le texte foncé. */
    override val photoVeil = 0xD1F6EFE2.toInt()
    override fun bgColors(colors: IntArray): IntArray {
        val paper = 0xFFF6EFE2.toInt()
        return intArrayOf(Skin.mix(colors[0], paper, 0.16f), Skin.mix(colors.getOrElse(1) { colors[0] }, paper, 0.10f), Skin.mix(colors.last(), paper, 0.06f))
    }
    override val decorAlpha = 56
}

/** Grandes cartes très arrondies aux tons doux tirés du thème, allumé = tuile pastel. */
private object PastelSkin : SkinDef("pastel", "Pastel") {
    private const val INK = 0xFF1F1A24.toInt()
    /** Surface « tonale » : gris sombre teinté par la couleur du thème. */
    private val tonal get() = Skin.mix(tint, 0xFF1B1D24.toInt(), 0.16f)
    private val on get() = Skin.mix(tint, Color.WHITE, 0.55f)
    override val usesTint = true
    override val tintFallback = 0xFFB8A6FF.toInt()
    override val tileH = 80
    override val tileGap = 14
    override val tilePad = 16
    override val tileRadius = 26f
    override val sliderRadius = 32f
    override val soft get() = a(tint, 0x33)
    override val accent get() = tint
    override val panel get() = a(Skin.mix(tint, 0xFF15171C.toInt(), 0.12f), 0xFA)
    override val panelStroke get() = a(tint, 0x33)
    override val primary get() = on
    override val onPrimary = INK
    override val sliderTrack get() = a(tint, 0x33)
    override val sliderFill get() = a(on, 0xE6)
    override val bold: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    override val light: Typeface get() = Typeface.create("sans-serif", Typeface.NORMAL)
    override fun card(ctx: Context, radius: Float): Drawable = rr(a(tonal, 0xE6), radius * 1.3f)
    override fun panelRadius(radius: Float) = radius * 1.2f
    override fun chip(ctx: Context, level: Int): Pair<Drawable, Int> = when (level) {
        2 -> rr(0xFFFFB4AB.toInt(), ctx.dpf(10)) to 0xFF410002.toInt()
        1 -> rr(0xFFFFDDB3.toInt(), ctx.dpf(10)) to 0xFF2A1800.toInt()
        else -> rr(a(tonal, 0xE6), ctx.dpf(10), ctx, a(tint, 0x40)) to Color.WHITE
    }
    override fun pill(ctx: Context, selected: Boolean): Pair<Drawable, Int> =
        if (selected) rr(on, ctx.dpf(40)) to INK else rr(soft, ctx.dpf(40)) to Color.WHITE
    override fun tile(active: Boolean, accent: Int): TileLook =
        if (active) TileLook(fill = on, iconCircle = Skin.mix(tint, Color.BLACK, 0.55f), iconTint = on, name = INK, status = a(INK, 0xB3))
        else TileLook(fill = tonal, iconCircle = a(tint, 0x33), iconTint = 0xE6FFFFFF.toInt(), name = Color.WHITE, status = 0xA6FFFFFF.toInt())
}

/** Fond très sombre, tuiles en contour, allumé = lueur de la couleur du thème, chiffres à chasse fixe. */
private object NeonSkin : SkinDef("neon", "Néon") {
    private const val DARK = 0x99040609.toInt()
    override val usesTint = true
    override val tintSaturation = 0.85f
    override val tintFallback = 0xFF35E0FF.toInt()
    override val tileRadius = 14f
    override val mediaRadius = 10f
    override val sliderRadius = 18f
    override val soft get() = a(tint, 0x1F)
    override val accent get() = tint
    override val panel get() = 0xF705070B.toInt()
    override val panelStroke get() = a(tint, 0x80)
    override val primary get() = tint
    override val onPrimary = 0xFF05070B.toInt()
    override val sliderTrack get() = 0x26FFFFFF
    override val sliderFill get() = a(tint, 0xD9)
    override val bold: Typeface get() = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    override val medium: Typeface get() = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    override val light: Typeface get() = Typeface.MONOSPACE
    override val clockFace: Typeface get() = Typeface.MONOSPACE
    override fun card(ctx: Context, radius: Float): Drawable = rr(DARK, radius * 0.65f, ctx, a(tint, 0x59))
    override fun panelRadius(radius: Float) = radius * 0.55f
    override fun softRect(ctx: Context, radius: Float): Drawable = rr(0, radius, ctx, a(tint, 0x80))
    override fun chip(ctx: Context, level: Int): Pair<Drawable, Int> {
        // Neutre : texte blanc et contour discret de la couleur du thème (pour ne pas ressembler à une alerte)
        val c = when (level) { 2 -> 0xFFFF4D6D.toInt(); 1 -> 0xFFFFC233.toInt(); else -> 0xE6FFFFFF.toInt() }
        return rr(DARK, ctx.dpf(8), ctx, if (level == 0) a(tint, 0x66) else a(c, 0xB3)) to c
    }
    override fun pill(ctx: Context, selected: Boolean): Pair<Drawable, Int> =
        if (selected) rr(tint, ctx.dpf(8)) to onPrimary else rr(0, ctx.dpf(8), ctx, a(tint, 0x80)) to tint
    override fun tile(active: Boolean, accent: Int): TileLook =
        if (active) TileLook(fill = DARK, stroke = a(tint, 0xE6), strokeDp = 1.5f, glow = tint, iconCircle = 0, iconTint = tint, iconScale = 0.6f,
            name = Color.WHITE, status = a(tint, 0xE6))
        else TileLook(fill = DARK, stroke = 0x2EFFFFFF, iconCircle = 0, iconTint = 0x80FFFFFF.toInt(), iconScale = 0.6f,
            name = 0xCCFFFFFF.toInt(), status = 0x80FFFFFF.toInt())
    override fun bgColors(colors: IntArray) =
        intArrayOf(Skin.mix(colors[0], Color.BLACK, 0.30f), Skin.mix(colors.getOrElse(1) { colors[0] }, Color.BLACK, 0.18f), Skin.mix(colors.last(), Color.BLACK, 0.10f))
    override val decorAlpha = 30
    override val photoVeil = 0xBF000000.toInt()
}

// ------------------------------------------------------------ façade

/** Skin en cours : ce que les écrans utilisent. */
object Skin {
    private val all: List<SkinDef> = listOf(HomeKitSkin, EpureSkin, PapierSkin, PastelSkin, NeonSkin, CompactSkin, ConfortSkin)
    val keys = all.map { it.key }
    val names get() = all.map { it.name }

    private var cur: SkinDef = HomeKitSkin
    val key get() = cur.key

    /** Couleur tirée du thème ou du fond d'écran (accent de Pastel et Néon). */
    var tint = 0xFFFFB300.toInt()
        private set

    /** Ce qui a servi à charger la skin : si ça change, l'écran est reconstruit. */
    var signature = ""
        private set

    private fun def(key: String) = all.firstOrNull { it.key == key } ?: HomeKitSkin
    fun signatureOf(cfg: ConfigStore) = def(cfg.activeSkin).let { it.key + if (it.usesTint) "|" + cfg.activeTheme + "|" + cfg.wallpaper + "|" + cfg.wallpaperRev else "" }

    fun load(cfg: ConfigStore) {
        cur = def(cfg.activeSkin)
        signature = signatureOf(cfg)
        val theme = Themes.get(cfg.activeTheme)
        val photo = if (cur.usesTint && theme == null && PhotoWallpaper.isPhoto(cfg.wallpaper)) PhotoWallpaper.averageColor(App.instance, cfg.wallpaper) else null
        tint = tintOf(theme?.colors ?: photo?.let { intArrayOf(it) } ?: Wallpapers.colorsOf(cfg.wallpaper))
    }

    /** Couleur vive tirée de la couleur la plus saturée du fond (ou la couleur de secours si le fond est gris). */
    private fun tintOf(colors: IntArray): Int {
        val hsv = FloatArray(3)
        val best = colors.maxByOrNull { Color.colorToHSV(it, hsv); hsv[1] * hsv[2] } ?: return cur.tintFallback
        Color.colorToHSV(best, hsv)
        if (hsv[1] < 0.15f) return cur.tintFallback
        hsv[1] = cur.tintSaturation
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    /** Mélange opaque de [c] (part [keep]) et de [base]. Calcul direct sur les octets, sans dépendre d'Android. */
    fun mix(c: Int, base: Int, keep: Float): Int {
        fun ch(shift: Int) = (((c shr shift) and 0xFF) * keep + ((base shr shift) and 0xFF) * (1 - keep)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Sélectionne une skin par sa clé (tests). */
    internal fun use(key: String) { cur = def(key) }

    // tailles
    val textScale get() = cur.textScale
    val tileMinW get() = cur.tileMinW
    val tileH get() = cur.tileH
    val tileGap get() = cur.tileGap
    val iconD get() = cur.iconD
    val tilePad get() = cur.tilePad
    val nameSize get() = cur.nameSize
    val statusSize get() = cur.statusSize
    val tileRadius get() = cur.tileRadius
    val mediaRadius get() = cur.mediaRadius
    val sliderRadius get() = cur.sliderRadius
    val chipRows get() = cur.chipRows
    val shortStatus get() = cur.shortStatus

    // couleurs
    /** Encre du texte et des icônes. */
    val ink get() = cur.ink
    /** Encre avec une transparence (0–255), pour les textes secondaires. */
    fun fg(alpha: Int): Int = (cur.ink and 0x00FFFFFF) or (cur.inkAlpha(alpha) shl 24)
    val mute get() = cur.mute
    val line get() = fg(cur.lineAlpha)
    /** Fond doux des boutons ronds, pilules inactives et pastilles neutres. */
    val soft get() = cur.soft
    /** Couleur d'accent de l'interface (minuteries, sélection). */
    val accent get() = cur.accent
    /** Fond des fenêtres et panneaux (contrôles, notifications, Ce matin…). */
    val panel get() = cur.panel
    /** Bouton principal (OK, Ouvrir les réglages) : fond et texte. */
    val primary get() = cur.primary
    val onPrimary get() = cur.onPrimary
    val sliderTrack get() = cur.sliderTrack
    val sliderFill get() = cur.sliderFill

    // polices
    val bold get() = cur.bold
    val medium get() = cur.medium
    val light get() = cur.light
    val clockFace get() = cur.clockFace

    // surfaces
    fun card(ctx: Context, radius: Float) = cur.card(ctx, radius)
    fun panelBg(ctx: Context, radius: Float): Drawable = GradientDrawable().apply {
        setColor(cur.panel); cornerRadius = cur.panelRadius(radius); setStroke(ctx.dp(1), cur.panelStroke)
    }
    fun softRect(ctx: Context, radius: Float) = cur.softRect(ctx, radius)
    fun chip(ctx: Context, level: Int) = cur.chip(ctx, level)
    fun chipColored(ctx: Context, color: Int) = cur.chipColored(ctx, color)
    fun pill(ctx: Context, selected: Boolean) = cur.pill(ctx, selected)
    fun tile(active: Boolean, accent: Int) = cur.tile(active, accent)
    val dialogOnSheet get() = cur.dialogOnSheet
    val dialogDim get() = cur.dialogDim

    // fond
    val photoVeil get() = cur.photoVeil

    /** Voile de la skin, renforcé ou allégé par le réglage [level] (0 à 100 ; 50 = tel que prévu par la skin). */
    fun photoVeil(level: Int): Int {
        val base = cur.photoVeil
        val alpha = ((base ushr 24) * (level.coerceIn(0, 100) / 50f)).toInt().coerceIn(0x1F, 0xEB)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }
    fun bgColors(colors: IntArray) = cur.bgColors(colors)
    val decorAlpha get() = cur.decorAlpha
}
