package com.homepanel.kiosk

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.random.Random

/**
 * Écran de veille : noir complet, ou l'heure (sans secondes), la date et la météo du moment.
 * Le bloc se déplace un peu chaque minute pour ne pas marquer l'écran.
 */
class DimScreen(ctx: Context) : FrameLayout(ctx) {
    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val time = ctx.label(150f, 0xE6FFFFFF.toInt()).apply {
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        includeFontPadding = false
    }
    private val date = ctx.label(26f, 0xB3FFFFFF.toInt())
    private val weather = ctx.label(30f, 0xD9FFFFFF.toInt()).apply { setPadding(0, ctx.dp(18), 0, 0) }
    private var lastMinute = ""

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        visibility = View.GONE
        for (v in listOf(time, date, weather)) {
            v.gravity = Gravity.CENTER
            box.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        addView(box, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }

    /** Met à jour le contenu ; [clock] = false donne un écran entièrement noir. */
    fun update(clock: Boolean, weatherState: EntityState?) {
        box.visibility = if (clock) View.VISIBLE else View.GONE
        if (!clock) return
        val now = Date()
        val t = SimpleDateFormat("HH:mm", I18n.locale).format(now)
        time.text = t
        date.text = SimpleDateFormat(if (I18n.english) "EEEE, MMMM d" else "EEEE d MMMM", I18n.locale).format(now)
            .replaceFirstChar { it.uppercase() }
        weather.text = weatherState?.let { w ->
            val temp = w.attrs.dbl("temperature")?.let { Present.num(it) + "°" } ?: ""
            "${HomeText.emoji(w.state)}  $temp  ${HomeText.condition(w.state)}".trim()
        } ?: ""
        weather.visibility = if (weather.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        // Léger déplacement à chaque nouvelle minute (anti-marquage).
        if (t != lastMinute) {
            lastMinute = t
            val r = Random.Default
            box.translationX = (r.nextFloat() - 0.5f) * width * 0.25f
            box.translationY = (r.nextFloat() - 0.5f) * height * 0.25f
        }
    }
}
