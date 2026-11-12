package com.homepanel.kiosk

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Listes d'épicerie et de tâches : les listes « À faire » de Home Assistant (entités todo.*).
 * Une icône dans le bandeau (avec le nombre d'articles restants) ouvre un panneau où l'on coche, ajoute et retire.
 */
object Todos {
    class Item(val uid: String, val summary: String, var done: Boolean)

    /** Articles d'une liste (todo.get_items) ; [cb] reçoit null si Home Assistant ne répond pas. */
    fun load(ha: HaClient, entity: String, cb: (List<Item>?) -> Unit) {
        val msg = JSONObject().put("type", "call_service").put("domain", "todo").put("service", "get_items")
            .put("target", JSONObject().put("entity_id", entity)).put("return_response", true)
        ha.request(msg) { res ->
            val arr = res.optJSONObject("result")?.optJSONObject("response")?.optJSONObject(entity)?.optJSONArray("items")
            if (arr == null) { cb(null); return@request }
            cb((0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val s = o.optString("summary")
                    if (s.isBlank()) null else Item(o.optString("uid").ifEmpty { s }, s, o.optString("status") == "completed")
                }
            })
        }
    }

    fun setDone(ha: HaClient, entity: String, item: Item, done: Boolean) =
        ha.callService("todo", "update_item", entity, JSONObject().put("item", item.uid).put("status", if (done) "completed" else "needs_action"))

    fun add(ha: HaClient, entity: String, text: String) = ha.callService("todo", "add_item", entity, JSONObject().put("item", text))

    fun removeDone(ha: HaClient, entity: String) = ha.callService("todo", "remove_completed_items", entity)

    /** Nombre d'articles restants dans les listes choisies (l'état d'une liste est ce nombre). */
    fun remaining(lists: List<String>, states: Map<String, EntityState>) = lists.sumOf { states[it]?.state?.toDoubleOrNull()?.toInt() ?: 0 }
}

/** Icône « Listes » du bandeau, avec le nombre d'articles restants. */
class TodoButton(ctx: Context) : FrameLayout(ctx) {
    private val badge = ctx.label(12f, Skin.onPrimary, true).apply {
        gravity = Gravity.CENTER
        background = roundRect(Skin.accent, ctx.dpf(10))
        setPadding(ctx.dp(5), 0, ctx.dp(5), 0)
        minWidth = ctx.dp(20)
    }

    init {
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_list)
            imageTintList = android.content.res.ColorStateList.valueOf(Skin.fg(0xE6))
        }, LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER))
        addView(badge, LayoutParams(WRAP_CONTENT, ctx.dp(20), Gravity.TOP or Gravity.END))
        setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        isClickable = true
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, oval(android.graphics.Color.WHITE))
        contentDescription = tr("Listes")
        visibility = View.GONE
    }

    fun set(visible: Boolean, count: Int) {
        visibility = if (visible) View.VISIBLE else View.GONE
        badge.text = if (count > 99) "99+" else count.toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }
}

/** Panneau des listes : une liste à la fois (onglets s'il y en a plusieurs), cocher, ajouter, retirer les cochés. */
class TodoPanel(private val act: MainActivity) : FrameLayout(act) {
    private val ha get() = App.instance.ha
    private val cfg get() = App.instance.config
    private val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val titleView = act.label(26f, Skin.ink, true)
    private val countView = act.label(15f, Skin.mute)
    private val input = EditText(act)
    private val clearBtn = act.label(15f, Skin.ink)
    private var entity = ""
    private var items: List<Todos.Item> = emptyList()
    private var gen = 0
    val showing get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xB3000000.toInt())
        isClickable = true
        setOnClickListener { hide() }
        elevation = act.dpf(28)
        visibility = View.GONE

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.panelBg(act, act.dpf(26))
            setPadding(act.dp(26), act.dp(22), act.dp(26), act.dp(18))
            isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView); addView(countView)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clearBtn.apply {
            text = tr("Retirer les cochés")
            typeface = Skin.medium
            background = roundRect(Skin.fg(0x24), act.dpf(18))
            setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9))
            setOnClickListener {
                if (items.none { it.done }) return@setOnClickListener
                Todos.removeDone(ha, entity)
                items = items.filter { !it.done }; render()
                reloadSoon()
            }
        }
        head.addView(clearBtn)
        head.addView(act.roundIcon(R.drawable.ic_close, 44, Skin.fg(0x1F), Skin.ink) { hide() }.apply {
            layoutParams = LinearLayout.LayoutParams(act.dp(44), act.dp(44)).apply { marginStart = act.dp(12) }
        })
        panel.addView(head)
        panel.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(12) })

        // Ajout : en haut du panneau, pour rester visible quand le clavier s'ouvre.
        val addRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input.apply {
            hint = tr("Ajouter un article…")
            textSize = 18f
            setTextColor(Skin.ink); setHintTextColor(Skin.fg(0x73))
            background = GradientDrawable().apply { setColor(Skin.fg(0x14)); cornerRadius = act.dpf(14) }
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { addItem(); true } else false }
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addRow.addView(act.label(17f, Skin.onPrimary, true, tr("Ajouter")).apply {
            gravity = Gravity.CENTER
            background = roundRect(Skin.accent, act.dpf(14))
            setPadding(act.dp(22), act.dp(13), act.dp(22), act.dp(13))
            setOnClickListener { addItem() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = act.dp(10) })
        panel.addView(addRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(14) })
        panel.addView(View(act).apply { setBackgroundColor(Skin.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = act.dp(14) })
        panel.addView(ScrollView(act).apply { addView(list); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val dm = act.resources.displayMetrics
        // Ancré en haut : le clavier couvre le bas de l'écran.
        addView(panel, LayoutParams(minOf(dm.widthPixels - act.dp(64), act.dp(720)), (dm.heightPixels * 0.86f).toInt(), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = (dm.heightPixels * 0.05f).toInt()
        })
    }

    fun show() {
        val lists = cfg.todoLists
        if (entity !in lists) entity = lists.firstOrNull() ?: return
        visibility = View.VISIBLE
        bringToFront()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
        renderTabs(); render(); reload()
    }

    fun hide() {
        if (!showing) return
        gen++
        hideKeyboard()
        animate().alpha(0f).setDuration(150).withEndAction { visibility = View.GONE }.start()
    }

    /** Une liste a changé dans Home Assistant (ajout depuis un téléphone, par exemple). */
    fun onEntities(ids: Set<String>) { if (showing && entity in ids) reload() }

    private fun hideKeyboard() {
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun name(id: String) = ha.states[id]?.name ?: id.substringAfter('.').replace('_', ' ')

    private fun renderTabs() {
        tabs.removeAllViews()
        val lists = cfg.todoLists
        tabs.visibility = if (lists.size > 1) View.VISIBLE else View.GONE
        for (id in lists) tabs.addView(act.pill(name(id), id == entity) { if (id != entity) { entity = id; items = emptyList(); renderTabs(); render(); reload() } })
    }

    private fun reload() {
        val my = ++gen
        val e = entity
        Todos.load(ha, e) { got ->
            if (my != gen || e != entity) return@load
            if (got == null) { countView.text = tr("Home Assistant ne répond pas") ; return@load }
            items = got; render()
        }
    }

    /** Après une action : Home Assistant confirme un instant plus tard, on relit la liste pour rester exact. */
    private fun reloadSoon() { val my = gen; postDelayed({ if (showing && my == gen) reload() }, 700) }

    private fun addItem() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        Todos.add(ha, entity, text)
        input.setText("")
        items = items + Todos.Item("", text, false); render()
        reloadSoon()
    }

    private fun render() {
        titleView.text = name(entity)
        val left = items.count { !it.done }
        countView.text = when (left) { 0 -> if (items.isEmpty()) tr("La liste est vide") else tr("Tout est coché"); 1 -> tr("1 restant"); else -> "$left " + tr("restants") }
        clearBtn.visibility = if (items.any { it.done }) View.VISIBLE else View.GONE
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(act.label(17f, Skin.mute, text = tr("Rien pour l'instant. Ajoutez un article ci-dessus.")).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(40), 0, 0) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        // À faire d'abord (par rayon du magasin quand c'est une liste d'épicerie), les cochés ensuite (estompés).
        val todo = items.filter { !it.done }
        val aisles = Aisles.group(todo) { it.summary }
        if (aisles == null) for (it in todo) list.addView(row(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(8) })
        else for ((name, group) in aisles) {
            list.addView(act.label(14f, Skin.fg(0xB3), true, tr(name)).apply { setPadding(act.dp(4), act.dp(14), 0, 0) })
            for (it in group) list.addView(row(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(6) })
        }
        val done = items.filter { it.done }
        if (aisles != null && done.isNotEmpty()) list.addView(act.label(14f, Skin.fg(0x80), true, tr("Cochés")).apply { setPadding(act.dp(4), act.dp(14), 0, 0) })
        for (it in done) list.addView(row(it), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = act.dp(8) })
    }

    private fun row(item: Todos.Item): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(14), act.dp(13), act.dp(16), act.dp(13))
            background = roundRect(Skin.fg(if (item.done) 0x08 else 0x14), act.dpf(16))
            isClickable = true
        }
        r.addView(act.label(18f, if (item.done) Skin.onPrimary else android.graphics.Color.TRANSPARENT, true, "✓").apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (item.done) Skin.accent else android.graphics.Color.TRANSPARENT)
                setStroke(act.dp(2), if (item.done) Skin.accent else Skin.fg(0x80))
            }
        }, LinearLayout.LayoutParams(act.dp(30), act.dp(30)).apply { marginEnd = act.dp(16) })
        r.addView(act.label(19f, Skin.fg(if (item.done) 0x80 else 0xFF), text = item.summary).apply {
            if (item.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.setOnClickListener {
            if (item.uid.isEmpty()) return@setOnClickListener   // tout juste ajouté : pas encore confirmé par Home Assistant
            item.done = !item.done
            Todos.setDone(ha, entity, item, item.done)
            render(); reloadSoon()
        }
        // Appui long : une minuterie au nom de l'article ou de la tâche (« Sortir le poulet », « Lessive »).
        r.setOnLongClickListener { if (!item.done) timerFor(item.summary); true }
        return r
    }

    private fun timerFor(name: String) {
        val d = android.app.Dialog(act)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(28), act.dp(22), act.dp(28), act.dp(24))
            background = Skin.panelBg(act, act.dpf(22))
        }
        box.addView(act.label(21f, Skin.ink, true, "⏱  " + tr("Minuterie pour") + " « $name »").apply { setPadding(0, 0, 0, act.dp(14)) })
        val flow = FlowLayout(act)
        for (m in listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)) flow.addView(act.label(18f, Skin.ink).apply {
            text = if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60}"
            typeface = Skin.medium
            background = roundRect(Skin.fg(0x24), act.dpf(16))
            setPadding(act.dp(20), act.dp(14), act.dp(20), act.dp(14))
            setOnClickListener {
                KitchenTimers.start(m, name = name)
                android.widget.Toast.makeText(act, tr("Minuterie démarrée"), android.widget.Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
        })
        box.addView(flow)
        act.showSheet(d, box)
    }
}
