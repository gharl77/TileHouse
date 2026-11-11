package com.homepanel.kiosk

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Clochette de l'en-tête : icône + pastille rouge avec le nombre de notifications non lues. */
class BellView(ctx: Context) : FrameLayout(ctx) {
    private val badge = ctx.label(12f, Skin.ink, true).apply {
        gravity = Gravity.CENTER
        background = roundRect(0xFFFF3B30.toInt(), ctx.dpf(10))
        setPadding(ctx.dp(5), 0, ctx.dp(5), 0)
        minWidth = ctx.dp(20)
    }

    init {
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_bell)
            imageTintList = ColorStateList.valueOf(Skin.fg(0xE6))
        }, LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER))
        addView(badge, LayoutParams(WRAP_CONTENT, ctx.dp(20), Gravity.TOP or Gravity.END))
        setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        isClickable = true
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Skin.fg(0x33)), null, oval(Skin.ink))
        visibility = View.GONE
    }

    fun setCount(n: Int) {
        badge.text = if (n > 99) "99+" else n.toString()
        visibility = if (n > 0) View.VISIBLE else View.GONE
        contentDescription = "$n " + tr("notifications non lues")
    }
}

/** « il y a 5 min », « hier à 18:05 »… */
fun relativeTime(t: Long): String {
    val d = (System.currentTimeMillis() - t) / 1000
    return when {
        d < 60 -> tr("à l'instant")
        d < 3600 -> if (I18n.english) "${d / 60} min ago" else "il y a ${d / 60} min"
        d < 86_400 -> if (I18n.english) "${d / 3600} h ago" else "il y a ${d / 3600} h"
        else -> java.text.SimpleDateFormat(if (I18n.english) "MMM d, HH:mm" else "d MMM, HH:mm", I18n.locale).format(java.util.Date(t))
    }
}

/**
 * Centre de notifications (plein écran) : liste des notifications non lues.
 * Toucher une notification l'ouvre (elle devient lue) ; mode sélection ; « Tout marquer comme lu ».
 */
class NotificationCenter(private val act: MainActivity, private val store: NoticeStore, private val open: (StoredNotice) -> Unit) : FrameLayout(act) {
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val countView = act.label(15f, Skin.mute)
    private val selectBtn = smallButton(tr("Sélectionner")) { selecting = !selecting; selected.clear(); render() }
    private val allBtn = smallButton(tr("Tout marquer comme lu")) { selecting = false; store.markAllRead() }
    private val footer = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, act.dp(14), 0, 0) }
    private val markSel = act.label(17f, Skin.onPrimary, true).apply {
        gravity = Gravity.CENTER
        setPadding(act.dp(28), act.dp(12), act.dp(28), act.dp(12))
        setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            val ids = selected.toList()
            selected.clear(); selecting = false
            store.markRead(ids)
        }
    }
    private var selecting = false
    private val thumbs = ArrayList<android.graphics.Bitmap>()
    private val selected = HashSet<Long>()
    private val onStore: () -> Unit = { if (showing) render() }

    val showing get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true
        setOnClickListener { hide() }
        elevation = act.dpf(28)
        visibility = View.GONE

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Skin.panel); cornerRadius = act.dpf(26); setStroke(act.dp(1), Skin.fg(0x26)) }
            setPadding(act.dp(26), act.dp(22), act.dp(26), act.dp(22))
            isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(act.label(26f, Skin.ink, true, tr("Notifications")))
            addView(countView)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(selectBtn)
        head.addView(allBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = act.dp(10) })
        head.addView(act.roundIcon(R.drawable.ic_close, 44, Skin.fg(0x1F), Skin.ink) { hide() }.apply {
            layoutParams = LinearLayout.LayoutParams(act.dp(44), act.dp(44)).apply { marginStart = act.dp(12) }
        })
        panel.addView(head)
        panel.addView(View(act).apply { setBackgroundColor(Skin.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = act.dp(16) })
        panel.addView(ScrollView(act).apply { addView(list); isVerticalScrollBarEnabled = false },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        footer.addView(markSel)
        panel.addView(footer)

        val dm = act.resources.displayMetrics
        addView(panel, LayoutParams(minOf(dm.widthPixels - act.dp(64), act.dp(760)), (dm.heightPixels * 0.82f).toInt(), Gravity.CENTER))
    }

    private fun smallButton(text: String, onClick: () -> Unit) = act.label(15f, Skin.ink).apply {
        this.text = text
        typeface = Skin.medium
        background = roundRect(Skin.fg(0x24), act.dpf(18))
        setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9))
        setOnClickListener { onClick() }
    }

    fun show() {
        selecting = false; selected.clear()
        if (onStore !in store.listeners) store.listeners.add(onStore)
        render()
        visibility = View.VISIBLE
        bringToFront()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
    }

    fun hide() {
        if (!showing) return
        store.listeners.remove(onStore)
        animate().alpha(0f).setDuration(150).withEndAction {
            visibility = View.GONE; list.removeAllViews()
            for (b in thumbs) b.recycle()
            thumbs.clear()
        }.start()
    }

    private fun render() {
        val items = store.unread
        selected.retainAll(items.map { it.id }.toSet())
        countView.text = when (items.size) {
            0 -> tr("Aucune notification non lue")
            1 -> tr("1 non lue")
            else -> "${items.size} " + tr("non lues")
        }
        selectBtn.text = if (selecting) tr("Annuler") else tr("Sélectionner")
        selectBtn.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        allBtn.visibility = if (items.isEmpty() || selecting) View.GONE else View.VISIBLE
        footer.visibility = if (selecting) View.VISIBLE else View.GONE
        markSel.text = tr("Marquer comme lu") + if (selected.isNotEmpty()) " (${selected.size})" else ""
        markSel.background = roundRect(if (selected.isEmpty()) 0x33FFB300 else Skin.accent, act.dpf(14))

        list.removeAllViews()
        for (b in thumbs) b.recycle()
        thumbs.clear()
        if (items.isEmpty()) {
            // Tout est lu : le centre se ferme de lui-même un instant après.
            postDelayed({ if (showing && store.count == 0) hide() }, 1200)
            list.addView(act.label(18f, Skin.mute, text = "🔔  " + tr("Tout est lu.")).apply {
                gravity = Gravity.CENTER; setPadding(0, act.dp(60), 0, act.dp(60))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        for (s in items) list.addView(row(s))
    }

    private fun row(s: StoredNotice): View {
        val n = s.notice
        val sel = s.id in selected
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
            background = roundRect(if (sel) 0x33FFB300 else Skin.fg(0x0F), act.dpf(16))
            isClickable = true
            setOnClickListener {
                if (selecting) { if (!selected.remove(s.id)) selected.add(s.id); render() }
                else open(s)
            }
            setOnLongClickListener { if (!selecting) { selecting = true; selected.add(s.id); render() }; true }
        }
        if (selecting) r.addView(act.label(20f, if (sel) Skin.onPrimary else Color.TRANSPARENT, true, "✓").apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (sel) Skin.accent else Color.TRANSPARENT)
                setStroke(act.dp(2), if (sel) Skin.accent else Skin.fg(0x80))
            }
        }, LinearLayout.LayoutParams(act.dp(30), act.dp(30)).apply { marginEnd = act.dp(14) })
        r.addView(act.label(24f, Skin.ink, text = n.icon).apply {
            gravity = Gravity.CENTER
            background = oval((n.color and 0x00FFFFFF) or 0x40000000)
        }, LinearLayout.LayoutParams(act.dp(50), act.dp(50)).apply { marginEnd = act.dp(14) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(act.label(17f, Skin.ink, true, n.title.ifEmpty { tr("Notification") }).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(act.label(13.5f, Skin.mute, text = relativeTime(s.time)).apply { setPadding(act.dp(10), 0, 0, 0) })
        col.addView(top)
        if (n.message.isNotEmpty()) col.addView(act.label(15f, Skin.fg(0xCC), text = n.message).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, act.dp(3), 0, 0)
        })
        r.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Alerte caméra : vignette de la capture prise au moment de la détection.
        n.imageFile?.let { decodeFile(it, act.dp(150)) }?.let { bmp ->
            thumbs.add(bmp)
            r.addView(ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundRect(0xFF000000.toInt(), act.dpf(12))
                clipToOutline = true
                setImageBitmap(bmp)
            }, LinearLayout.LayoutParams(act.dp(128), act.dp(80)).apply { marginStart = act.dp(12) })
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, act.dp(5), 0, act.dp(5))
            addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }
}
