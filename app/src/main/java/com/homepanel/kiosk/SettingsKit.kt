package com.homepanel.kiosk

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Éléments visuels des réglages, façon iOS : cartes arrondies, lignes avec une
 * pastille d'icône colorée, interrupteurs et boutons aux couleurs de TileHouse.
 */
object Ui {
    const val CARD = 0xFF161D2A.toInt()
    const val INPUT = 0xFF0E141F.toInt()
    const val LINE = 0x1AFFFFFF
    const val TEXT = 0xFFF2F2F7.toInt()
    const val MUTE = 0xFF8E95A3.toInt()
    const val ACCENT = 0xFFFFB300.toInt()
    const val NAVY = 0xFF0F2E4A.toInt()
    const val OK = 0xFF30D158.toInt()
    const val BAD = 0xFFFF453A.toInt()

    // Pastilles d'icônes
    const val BLUE = 0xFF0A84FF.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val PURPLE = 0xFFBF5AF2.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val TEAL = 0xFF40C8E0.toInt()
    const val INDIGO = 0xFF5E5CE6.toInt()
    const val PINK = 0xFFFF375F.toInt()
    const val GRAY = 0xFF8E8E93.toInt()
    val ROOM_COLORS = intArrayOf(BLUE, ORANGE, GREEN, PURPLE, TEAL, PINK, INDIGO, 0xFFFFD60A.toInt())
}

private fun ripple(content: android.graphics.drawable.Drawable?, radius: Float) =
    RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), content, roundRect(Color.WHITE, radius))

/** Pastille carrée arrondie avec une icône blanche. */
fun Activity.iconBadge(iconRes: Int, color: Int, sizeDp: Int = 32) = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(Color.WHITE)
    background = roundRect(color, dpf(sizeDp * 0.28f))
    val pad = dp(sizeDp) / 5
    setPadding(pad, pad, pad, pad)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Logo de l'application (icône adaptative, masquée comme sur le lanceur). */
fun Activity.appLogo(sizeDp: Int) = ImageView(this).apply {
    setImageResource(R.mipmap.ic_launcher)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Petit titre gris au-dessus d'une carte. */
fun Activity.groupLabel(text: String) = TextView(this).apply {
    this.text = text.uppercase(I18n.locale)
    textSize = 12.5f
    letterSpacing = 0.06f
    setTextColor(Ui.MUTE)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setPadding(dp(16), dp(22), dp(16), dp(8))
}

/** Texte d'aide gris sous une carte. */
fun Activity.footnote(text: String) = TextView(this).apply {
    this.text = text
    textSize = 13.5f
    setTextColor(Ui.MUTE)
    setLineSpacing(0f, 1.15f)
    setPadding(dp(16), dp(8), dp(16), dp(4))
}

/** Carte arrondie ; ajouter les lignes avec [addRow] pour avoir les séparateurs. */
class Card(val act: Activity) : LinearLayout(act) {
    init {
        orientation = VERTICAL
        background = roundRect(Ui.CARD, act.dpf(16))
        clipToOutline = true
    }

    fun addRow(v: View): View {
        if (childCount > 0) addView(View(act).apply { setBackgroundColor(Ui.LINE) },
            LayoutParams(MATCH_PARENT, 1).apply { marginStart = act.dp(62) })
        addView(v, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return v
    }
}

/**
 * Ligne de réglage : pastille, titre, sous-titre facultatif et un contrôle à droite.
 * Si [onClick] est fourni, la ligne entière est cliquable (avec un chevron si pas de contrôle).
 */
fun Activity.settingRow(
    iconRes: Int?, color: Int, title: String, subtitle: String? = null,
    trailing: View? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(58)
    setPadding(dp(16), dp(10), dp(14), dp(10))
    if (iconRes != null) addView(iconBadge(iconRes, color), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(TextView(context).apply {
        text = title; textSize = 16.5f; setTextColor(Ui.TEXT)
    })
    if (subtitle != null) texts.addView(TextView(context).apply {
        text = subtitle; textSize = 13.5f; setTextColor(Ui.MUTE); setPadding(0, dp(2), 0, 0)
        tag = "subtitle"
    })
    addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    if (trailing != null) addView(trailing)
    if (chevron) addView(ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron)
        imageTintList = ColorStateList.valueOf(0x66FFFFFF)
    }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(6) })
    if (onClick != null) {
        isClickable = true
        background = ripple(null, 0f)
        setOnClickListener { onClick() }
    }
}

/** Remplace le sous-titre d'une ligne créée par [settingRow]. */
fun View.setSubtitle(text: String) { (findViewWithTag<TextView>("subtitle"))?.text = text }

/** Interrupteur aux couleurs de l'app. */
fun Activity.toggle(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
    isChecked = checked
    thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, 0xFFD0D0D6.toInt()))
    trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.OK, 0xFF3A3A40.toInt()))
    setOnCheckedChangeListener { _, c -> onChange(c) }
}

/** Liste déroulante compacte, alignée à droite d'une ligne. */
fun Activity.choice(items: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    spinner(items, selected, onSelect).apply {
        background = roundRect(0x14FFFFFF, dpf(10))
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setPopupBackgroundDrawable(roundRect(0xFF222B3A.toInt(), dpf(12)))
    }

/** Champ de saisie sombre, avec étiquette au-dessus, à placer dans une carte. */
fun Activity.labeledField(label: String, field: EditText): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(12))
    addView(TextView(context).apply { text = label; textSize = 13.5f; setTextColor(Ui.MUTE); setPadding(dp(2), 0, 0, dp(6)) })
    field.apply {
        textSize = 16f
        setTextColor(Ui.TEXT)
        setHintTextColor(0x66FFFFFF)
        background = roundRect(Ui.INPUT, dpf(10))
        setPadding(dp(14), dp(11), dp(14), dp(11))
    }
    addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

/** Bouton plein : ambre (principal) ou verre (secondaire). */
fun Activity.actionButton(text: String, iconRes: Int? = null, primary: Boolean = true, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    setPadding(dp(20), dp(12), dp(20), dp(12))
    val fg = if (primary) 0xFF1C1C1E.toInt() else Ui.TEXT
    background = ripple(roundRect(if (primary) Ui.ACCENT else 0x1FFFFFFF, dpf(12)), dpf(12))
    if (iconRes != null) addView(ImageView(context).apply {
        setImageResource(iconRes); imageTintList = ColorStateList.valueOf(fg)
    }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
    addView(TextView(context).apply {
        this.text = text; textSize = 15.5f; setTextColor(fg)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    })
    isClickable = true
    setOnClickListener { onClick() }
}

/** Petit bouton rond avec icône (monter, descendre, menu…). */
fun Activity.iconButton(iconRes: Int, tint: Int = 0xB3FFFFFF.toInt(), onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(tint)
    val p = dp(9)
    setPadding(p, p, p, p)
    background = ripple(null, dpf(20))
    isClickable = true
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
}

/** Contrôle segmenté (onglets). */
class Segmented(act: Activity, titles: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(act) {
    private val items = ArrayList<TextView>()
    init {
        orientation = HORIZONTAL
        background = roundRect(0x1AFFFFFF, act.dpf(12))
        setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
        titles.forEachIndexed { i, t ->
            val tv = TextView(act).apply {
                text = t; textSize = 15f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { select(i) }
            }
            items.add(tv)
            addView(tv, LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }
    fun select(i: Int) {
        items.forEachIndexed { k, tv ->
            val on = k == i
            tv.background = if (on) GradientDrawable().apply { setColor(0xFF3A4458.toInt()); cornerRadius = context.dpf(10) } else null
            tv.setTextColor(if (on) Color.WHITE else Ui.MUTE)
        }
        onSelect(i)
    }
}

/** Colonne centrée de largeur limitée (lisible sur grand écran). */
fun Activity.centeredColumn(maxWidthDp: Int, inner: View): View = FrameLayout(this).apply {
    addView(inner, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(32), dp(maxWidthDp)), WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
}
