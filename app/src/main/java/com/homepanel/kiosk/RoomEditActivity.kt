package com.homepanel.kiosk

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import java.util.Collections

/** Choix et ordre des appareils d'une pièce. */
class RoomEditActivity : Activity() {
    companion object {
        const val EXTRA_ROOM = "room"
        private val TYPES = listOf(
            tr("Tous les types") to emptySet(),
            tr("Caméras") to setOf("camera"),
            tr("Lumières") to setOf("light"),
            tr("Prises et interrupteurs") to setOf("switch", "input_boolean"),
            tr("Volets et stores") to setOf("cover"),
            tr("Climat et ventilateurs") to setOf("climate", "fan"),
            "Serrures" to setOf("lock"),
            tr("Capteurs") to setOf("sensor", "binary_sensor"),
            tr("Médias") to setOf("media_player"),
            tr("Scènes, scripts, boutons") to setOf("scene", "script", "button", "input_button", "automation"),
        )
    }

    private val app get() = App.instance
    private val cfg get() = app.config
    private lateinit var room: Room
    private var catalog: Catalog? = null

    private lateinit var nameEdit: EditText
    private lateinit var searchEdit: EditText
    private lateinit var areaBox: LinearLayout
    private lateinit var hiddenCheck: CheckBox
    private lateinit var info: TextView
    private val adapter = EntityAdapter()
    private lateinit var list: ListView

    private var rows: List<CatalogEntity> = emptyList()
    private var areaFilter: String? = null // null = toutes, "" = sans zone
    private var typeFilter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ROOM)
        room = cfg.rooms.firstOrNull { it.id == id } ?: run { finish(); return }
        title = room.name
        actionBar?.setDisplayHomeAsUpEnabled(true)
        areaFilter = room.areaId

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        nameEdit = field(tr("Nom de la pièce"), room.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        col.addView(nameEdit)
        searchEdit = field(tr("Rechercher un appareil…"), "", InputType.TYPE_CLASS_TEXT)
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        col.addView(searchEdit)
        areaBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(areaBox)
        hiddenCheck = CheckBox(this).apply {
            text = tr("Afficher aussi les entités masquées et de diagnostic")
            setOnCheckedChangeListener { _, _ -> applyFilter() }
        }
        col.addView(hiddenCheck)
        info = note(tr("Chargement de la liste des appareils…"))
        col.addView(info)

        list = ListView(this).apply {
            adapter = this@RoomEditActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> rename(rows[pos]); true }
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(col)

        buildFilters()
        applyFilter()
        CatalogLoader.load(app) { c, err ->
            if (isFinishing || isDestroyed) return@load
            catalog = c
            if (c == null) info.text = tr("Impossible de charger la liste") + " : ${err ?: "?"}. " + tr("Vérifiez la connexion dans les réglages.")
            buildFilters()
            applyFilter()
        }
    }

    override fun onPause() {
        super.onPause()
        val n = nameEdit.text.toString().trim()
        if (n.isNotEmpty()) room.name = n
        cfg.saveRooms()
        app.ha.setEntities(cfg.allEntityIds())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun buildFilters() {
        areaBox.removeAllViews()
        val areas = catalog?.areas ?: emptyList()
        val names = listOf(tr("Toutes les zones"), tr("Sans zone")) + areas.map { it.name }
        val sel = when (areaFilter) {
            null -> 0
            "" -> 1
            else -> areas.indexOfFirst { it.id == areaFilter }.let { if (it < 0) 0 else it + 2 }
        }
        if (sel == 0) areaFilter = null
        val areaSpinner: Spinner = spinner(names, sel) { i ->
            areaFilter = when (i) { 0 -> null; 1 -> ""; else -> areas[i - 2].id }
            applyFilter()
        }
        val typeSpinner: Spinner = spinner(TYPES.map { it.first }, typeFilter) { i -> typeFilter = i; applyFilter() }
        areaBox.addView(areaSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        areaBox.addView(typeSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun applyFilter() {
        val cat = catalog
        val q = searchEdit.text.toString().trim().lowercase()
        val selectedIds = room.entities.map { it.id }.toHashSet()
        val selected = room.entities.map { re -> cat?.byId?.get(re.id) ?: CatalogEntity(re.id, re.id, null, "", false) }
        val types = TYPES[typeFilter].second
        val showHidden = hiddenCheck.isChecked
        val af = areaFilter
        val others = (cat?.entities ?: emptyList()).filter { e ->
            e.id !in selectedIds &&
                (showHidden || !e.hidden) &&
                (types.isEmpty() || e.domain in types) &&
                (af == null || (af.isEmpty() && e.areaId == null) || e.areaId == af) &&
                (q.isEmpty() || e.name.lowercase().contains(q) || e.id.contains(q))
        }
        rows = selected + others
        adapter.notifyDataSetChanged()
        if (cat != null) {
            info.text = "${selected.size} " + tr("appareil(s) dans la pièce, en haut de la liste. Touchez pour ajouter ou retirer, appui long pour renommer, ▲▼ pour l'ordre.")
        }
    }

    private fun toggle(e: CatalogEntity) {
        val re = room.find(e.id)
        if (re != null) room.entities.remove(re) else room.entities.add(RoomEntity(e.id))
        applyFilter()
    }

    private fun rename(e: CatalogEntity) {
        val re = room.find(e.id) ?: run { toast(tr("Ajoutez d'abord l'appareil à la pièce.")); return }
        promptText(tr("Nom affiché"), re.name ?: "", e.name) { n ->
            re.name = n.ifEmpty { null }
            applyFilter()
        }
    }

    private fun move(id: String, d: Int) {
        val i = room.entities.indexOfFirst { it.id == id }
        val j = i + d
        if (i < 0 || j !in room.entities.indices) return
        Collections.swap(room.entities, i, j)
        applyFilter()
    }

    private inner class Holder {
        val check = CheckBox(this@RoomEditActivity).apply { isClickable = false; isFocusable = false }
        val name = TextView(this@RoomEditActivity).apply { textSize = 16f }
        val sub = TextView(this@RoomEditActivity).apply { textSize = 12f; alpha = 0.6f }
        var id = ""
        lateinit var root: LinearLayout
        val grip = dragGrip({ root }) { y -> dropEntity(id, y) }
        val up = flatButton("▲") { move(id, -1) }
        val down = flatButton("▼") { move(id, 1) }
        init {
            root = LinearLayout(this@RoomEditActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(0, dp(6), 0, dp(6))
            addView(grip)
            addView(check)
            addView(LinearLayout(this@RoomEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(sub)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(up)
            addView(down)
            tag = this@Holder
            }
        }
    }

    /** Glisser-déposer d'un appareil de la pièce vers la ligne située sous y (coordonnées de la liste). */
    private fun dropEntity(id: String, y: Float) {
        val from = room.entities.indexOfFirst { it.id == id }
        if (from < 0) return
        var pos = list.pointToPosition(list.width / 2, y.toInt())
        if (pos == ListView.INVALID_POSITION) pos = if (y < 0) 0 else room.entities.size - 1
        val to = pos.coerceIn(0, room.entities.size - 1) // seulement parmi les appareils de la pièce (en tête)
        if (to != from) {
            val e = room.entities.removeAt(from)
            room.entities.add(to, e)
        }
        applyFilter()
    }

    private inner class EntityAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? Holder) ?: Holder()
            val e = rows[position]
            val re = room.find(e.id)
            h.id = e.id
            h.check.isChecked = re != null
            h.name.text = if (re?.name != null) "${re.name}  (${e.name})" else e.name
            val area = catalog?.areaName(e.areaId)
            h.sub.text = if (area != null) "${e.id} · $area" else e.id
            val v = if (re != null) View.VISIBLE else View.GONE
            h.up.visibility = v
            h.down.visibility = v
            h.grip.visibility = if (re != null) View.VISIBLE else View.INVISIBLE
            return h.root
        }
    }
}
