package com.homepanel.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Collections

class SettingsActivity : Activity(), HaClient.Listener {
    private val app get() = App.instance
    private val cfg get() = app.config

    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var statusDot: TextView
    private lateinit var statusRow: View
    private lateinit var mqttRow: View
    private lateinit var webRow: View
    private lateinit var roomsBox: Card
    private lateinit var pinEdit: EditText
    private lateinit var versionText: TextView
    private lateinit var infoCard: Card
    private lateinit var memAppRow: LinearLayout
    private lateinit var memFreeRow: LinearLayout
    private var scroll: ScrollView? = null
    private lateinit var updRow: LinearLayout
    private lateinit var updButton: LinearLayout
    private val onUpdate: () -> Unit = { updateUpdateRow() }
    private var pinAtOpen = ""
    private val onConfigChanged: () -> Unit = { refreshRooms(); updateWebText() }

    private val dimChoices = listOf(0, 1, 2, 5, 10, 15, 30, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- En-tête fixe : retour, logo, nom de l'app
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(24), dp(10))
        }
        header.addView(iconButton(R.drawable.ic_back, Color.WHITE) { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
        })
        header.addView(appLogo(46))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(context).apply {
                text = getString(R.string.app_name); textSize = 24f; setTextColor(Ui.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply { text = tr("Réglages"); textSize = 14f; setTextColor(Ui.MUTE) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        statusDot = TextView(this).apply {
            textSize = 13.5f; setTextColor(Ui.TEXT)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            background = roundRect(0x1AFFFFFF, dpf(20))
        }
        header.addView(statusDot)

        val pages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ================= Pièces
        val rooms = section()
        rooms.addView(groupLabel(tr("Pièces")))
        roomsBox = Card(this)
        rooms.addView(roomsBox)
        rooms.addView(footnote(tr("Touchez une pièce pour choisir ses appareils. Glissez ≡ pour changer l'ordre.")))
        rooms.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
            addView(actionButton(tr("Ajouter une pièce"), R.drawable.ic_plus) { addRoom() })
            addView(actionButton(tr("Importer les zones de Home Assistant"), R.drawable.ic_download, primary = false) { importAreas() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
        })

        // ================= Affichage
        val display = section()
        display.addView(groupLabel(tr("Apparence")))
        val look = Card(this).also { display.addView(it) }
        val langKeys = listOf("auto", "fr", "en")
        look.addRow(settingRow(R.drawable.ic_globe, Ui.BLUE, tr("Langue"), trailing = choice(
            listOf(tr("Automatique"), "Français", "English"), langKeys.indexOf(cfg.language).coerceAtLeast(0)) {
            if (langKeys[it] != cfg.language) { cfg.language = langKeys[it]; app.notifyConfigChanged(); recreate() }
        }))
        val themeKeys = listOf("") + Themes.all.map { it.key }
        val themeNames = listOf(tr("Aucun")) + Themes.all.map { "${it.emoji}  ${it.name}" }
        look.addRow(settingRow(R.drawable.ic_star, Ui.ORANGE, tr("Thème"), tr("Saison ou fête, remplace le fond d'écran"), choice(
            themeNames, themeKeys.indexOf(cfg.theme).coerceAtLeast(0)) { cfg.theme = themeKeys[it]; app.notifyConfigChanged() }))
        look.addRow(settingRow(R.drawable.ic_image, Ui.PURPLE, tr("Fond d'écran"), trailing = choice(
            Wallpapers.names, Wallpapers.keys.indexOf(cfg.wallpaper)) { cfg.wallpaper = Wallpapers.keys[it]; app.notifyConfigChanged() }))

        display.addView(groupLabel(tr("Tableau de bord")))
        val board = Card(this).also { display.addView(it) }
        board.addRow(settingRow(R.drawable.ic_grid, Ui.TEAL, tr("Regrouper par catégorie"), tr("Lumières, Climat, Médias…"),
            toggle(cfg.groupByCategory) { cfg.groupByCategory = it }))
        board.addRow(settingRow(R.drawable.ic_clock, Ui.INDIGO, tr("Afficher l'heure et la date"), trailing = toggle(cfg.showClock) { cfg.showClock = it }))
        board.addRow(settingRow(R.drawable.ic_moon, Ui.GRAY, tr("Mise en veille"), tr("Assombrir après une période d'inactivité ; un toucher réveille l'écran"), choice(
            dimChoices.map { if (it == 0) tr("Jamais") else "$it min" }, dimChoices.indexOf(cfg.dimMinutes).coerceAtLeast(0)) {
            cfg.dimMinutes = dimChoices[it]; app.notifyConfigChanged()
        }))
        display.addView(groupLabel(tr("Caméras")))
        val camCard = Card(this).also { display.addView(it) }
        val alertChoices = listOf(10, 20, 30, 60)
        camCard.addRow(settingRow(R.drawable.ic_monitor, Ui.RED, tr("Alerte personne détectée"),
            tr("Affiche le live de la caméra en plein écran quand elle détecte une personne"), toggle(cfg.personAlert) { cfg.personAlert = it }))
        val trigKeys = listOf("person", "motion")
        camCard.addRow(settingRow(R.drawable.ic_monitor, Ui.PURPLE, tr("Déclencher sur"), trailing = choice(
            listOf(tr("Personne seulement"), tr("Tout mouvement")), trigKeys.indexOf(cfg.alertTrigger).coerceAtLeast(0)) { cfg.alertTrigger = trigKeys[it] }))
        camCard.addRow(settingRow(R.drawable.ic_clock, Ui.ORANGE, tr("Durée de l'alerte"), tr("Touchez l'écran pendant l'alerte pour garder le live ouvert"), choice(
            alertChoices.map { "$it s" }, alertChoices.indexOf(cfg.alertSeconds).coerceAtLeast(1)) { cfg.alertSeconds = alertChoices[it] }))
        display.addView(footnote(tr("La météo, l'agenda, les personnes et l'ordre des paliers de l'accueil se règlent dans la page web (onglet Accueil).")))

        // ================= Connexion
        val conn = section()
        conn.addView(groupLabel("Home Assistant"))
        val ha = Card(this).also { conn.addView(it) }
        statusRow = settingRow(R.drawable.ic_home, Ui.BLUE, tr("État"), "…")
        ha.addRow(statusRow)
        urlEdit = field(tr("ex. http://192.168.1.10:8123"), if (cfg.demo) "" else cfg.url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        tokenEdit = field(if (cfg.token.isEmpty()) tr("Jeton d'accès longue durée") else tr("•••••••• jeton enregistré (laisser vide pour le garder)"), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        ha.addRow(labeledField(tr("Adresse"), urlEdit))
        ha.addRow(labeledField(tr("Jeton d'accès"), tokenEdit))
        conn.addView(footnote(tr("Créer un jeton : Home Assistant → votre profil (en bas à gauche) → onglet Sécurité → Jetons d'accès longue durée → Créer un jeton.")))
        conn.addView(LinearLayout(this).apply {
            setPadding(0, dp(14), 0, 0)
            addView(actionButton(tr("Enregistrer et se connecter"), R.drawable.ic_link) { saveConnection() })
        })
        conn.addView(groupLabel(tr("Démonstration")))
        val demoCard = Card(this).also { conn.addView(it) }
        demoCard.addRow(settingRow(R.drawable.ic_star, Ui.PINK, tr("Mode démo"), tr("Maison fictive, sans Home Assistant. Votre configuration revient quand vous quittez la démo."),
            toggle(cfg.demo) { app.setDemo(it); updateStatus() }))
        conn.addView(groupLabel("MQTT"))
        val mq = Card(this).also { conn.addView(it) }
        mqttRow = settingRow(R.drawable.ic_chat, Ui.GREEN, tr("Appareil dans Home Assistant"), "…")
        mq.addRow(mqttRow)
        conn.addView(footnote(tr("Le serveur MQTT se configure dans la page web (onglet Connexion et sécurité).")))

        // ================= Kiosque et sécurité
        val kiosk = section()
        kiosk.addView(groupLabel(tr("Page web de configuration")))
        val web = Card(this).also { kiosk.addView(it) }
        webRow = settingRow(R.drawable.ic_monitor, Ui.BLUE, tr("Activer la page web"), "", toggle(cfg.webEnabled) {
            cfg.webEnabled = it; app.updateWebServer(); updateWebText()
        })
        web.addRow(webRow)

        kiosk.addView(groupLabel(tr("Sécurité")))
        val sec = Card(this).also { kiosk.addView(it) }
        pinEdit = field(if (cfg.pin.isEmpty()) tr("Nouveau code (chiffres)") else tr("•••• code enregistré (laisser vide pour le garder)"), "", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        sec.addRow(labeledField(tr("Code PIN (protège ces réglages et la page web)"), pinEdit))
        sec.addRow(settingRow(R.drawable.ic_trash, Ui.RED, tr("Retirer le code PIN"), onClick = {
            cfg.pin = ""; pinEdit.setText(""); pinEdit.hint = tr("Nouveau code (chiffres)")
            toast(tr("Code PIN retiré.")); updateWebText()
        }))
        pinAtOpen = cfg.pin

        kiosk.addView(groupLabel(tr("Mode kiosque")))
        val kc = Card(this).also { kiosk.addView(it) }
        kc.addRow(settingRow(R.drawable.ic_home, Ui.ORANGE, tr("Définir comme application d'accueil"),
            tr("Démarre avec la tablette ; le bouton Accueil y ramène toujours"), chevron = true) {
            try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        kc.addRow(settingRow(R.drawable.ic_gear, Ui.GRAY, tr("Ouvrir les paramètres Android"), chevron = true) { startActivity(Intent(Settings.ACTION_SETTINGS)) })

        // ================= À propos
        val aboutPage = section()
        aboutPage.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(26), 0, dp(6))
            addView(appLogo(96))
            addView(TextView(context).apply {
                text = getString(R.string.app_name); textSize = 28f; setTextColor(Ui.TEXT); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(12), 0, 0)
            })
            versionText = TextView(context).apply { textSize = 15f; setTextColor(Ui.ACCENT); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }
            addView(versionText)
            addView(TextView(context).apply {
                text = tr("Tableau de bord Home Assistant façon HomeKit, pour tablettes Android murales.")
                textSize = 14.5f; setTextColor(Ui.MUTE); gravity = Gravity.CENTER; setPadding(dp(24), dp(8), dp(24), 0)
            })
        })

        aboutPage.addView(groupLabel(tr("Liens")))
        val links = Card(this).also { aboutPage.addView(it) }
        links.addRow(settingRow(R.drawable.ic_link, Ui.GRAY, "GitHub", About.REPO_URL.removePrefix("https://"), chevron = true) { openUrl(About.REPO_URL) })
        links.addRow(settingRow(R.drawable.ic_star, Ui.ORANGE, tr("Notes de version"), tr("Nouveautés de chaque version"), chevron = true) { openUrl(About.RELEASES_URL) })
        links.addRow(settingRow(R.drawable.ic_chat, Ui.BLUE, tr("Signaler un problème ou suggérer une idée"), tr("GitHub Issues"), chevron = true) { openUrl(About.ISSUES_URL) })

        aboutPage.addView(groupLabel(tr("Mises à jour")))
        val upd = Card(this).also { aboutPage.addView(it) }
        updButton = actionButton(tr("Rechercher"), primary = false) {
            if (app.updater.updateAvailable) app.updater.installLatest()
            else { updRow.setSubtitle(tr("Recherche…")); app.updater.check() }
        }
        updRow = settingRow(R.drawable.ic_download, Ui.GREEN, tr("Mises à jour"), "…", updButton)
        upd.addRow(updRow)

        aboutPage.addView(groupLabel(tr("Informations")))
        infoCard = Card(this).also { aboutPage.addView(it) }
        memAppRow = settingRow(R.drawable.ic_chip, Ui.PURPLE, tr("Mémoire utilisée par l'application"), trailing = valueText())
        memFreeRow = settingRow(R.drawable.ic_chip, Ui.TEAL, tr("Mémoire libre sur la tablette"), trailing = valueText())
        aboutPage.addView(groupLabel(tr("Mémoire")))
        val mem = Card(this).also { aboutPage.addView(it) }
        mem.addRow(memAppRow)
        mem.addRow(memFreeRow)
        aboutPage.addView(footnote(tr("Logiciel libre sous licence MIT. TileHouse n'est affilié ni à Apple ni à Home Assistant.")))

        // --- Assemblage : en-tête et onglets fixes, contenu défilant
        val all = listOf(rooms, display, conn, kiosk, aboutPage)
        val tabs = Segmented(this, listOf(tr("Pièces"), tr("Affichage"), tr("Connexion"), tr("Kiosque"), tr("À propos"))) { i ->
            pages.removeAllViews(); pages.addView(all[i])
            if (i == 4) updateMemory()
            scroll?.scrollTo(0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(60))
            addView(pages)
        }
        val sc = ScrollView(this).apply { isFillViewport = true; addView(centeredColumn(820, content)) }
        scroll = sc
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Wallpapers.drawable("nuit")
            addView(header)
            addView(centeredColumn(820, tabs), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
            addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        tabs.select(if (cfg.isConfigured) 0 else 2)
        setContentView(root)
    }

    /** Ouvre un lien dans le navigateur ; sans navigateur, affiche l'adresse. */
    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { toast(url) }
    }

    private fun valueText() = TextView(this).apply { textSize = 15f; setTextColor(Ui.MUTE) }

    override fun onResume() {
        super.onResume()
        app.ha.addListener(this)
        app.configListeners.add(onConfigChanged)
        app.updater.listeners.add(onUpdate)
        updateUpdateRow()
        updateStatus()
        refreshRooms()
        updateMemory()
        updateWebText()
    }

    private fun updateWebText() {
        val url = app.web.url()
        webRow.setSubtitle(when {
            !cfg.webEnabled -> tr("Page désactivée.")
            url == null -> tr("Page active, mais la tablette n'a pas d'adresse sur le réseau (Wi-Fi coupé ?).")
            else -> tr("Depuis un ordinateur ou un téléphone du même Wi-Fi :") + "\n$url" +
                if (cfg.pin.isEmpty()) "\n" + tr("Conseil : définissez un code PIN pour protéger la page.") else "\n" + tr("La page demandera le code PIN.")
        })
        (webRow.findViewWithTag<TextView>("subtitle"))?.apply { setTextIsSelectable(true) }
        updateMqtt()
    }

    private fun updateUpdateRow() {
        val u = app.updater
        val latest = u.latest?.version
        updRow.setSubtitle(when {
            u.status.isNotEmpty() -> u.status
            u.updateAvailable -> tr("Nouvelle version disponible :") + " $latest"
            latest != null -> tr("TileHouse est à jour") + " ($latest)"
            else -> tr("Vérification automatique toutes les 6 heures (GitHub)")
        })
        (updButton.getChildAt(updButton.childCount - 1) as TextView).text =
            if (u.updateAvailable) tr("Installer") + " $latest" else tr("Rechercher")
        updButton.isEnabled = !u.busy
        updButton.alpha = if (u.busy) 0.5f else 1f
    }

    private fun updateMqtt() {
        val st = app.mqtt.status
        mqttRow.setSubtitle(cfg.deviceName + " · " + st)
    }

    override fun onPause() {
        super.onPause()
        app.ha.removeListener(this)
        app.configListeners.remove(onConfigChanged)
        app.updater.listeners.remove(onUpdate)
        // Un code saisi remplace l'ancien ; champ vide = inchangé (bouton « Retirer » pour l'enlever)
        val p = pinEdit.text.toString().trim()
        if (p.isNotEmpty()) { cfg.pin = p; pinEdit.setText(""); pinEdit.hint = tr("•••• code enregistré (laisser vide pour le garder)") }
    }

    override fun onDestroy() {
        super.onDestroy()
        app.catalog = null // libère la mémoire de la liste complète
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onHaStatus(status: HaClient.Status) = updateStatus()

    private fun updateStatus() {
        val ha = app.ha
        val (txt, color) = if (ha.demo) tr("Mode démo : maison fictive, aucune connexion à Home Assistant.") to Ui.PINK else when (ha.status) {
            HaClient.Status.IDLE -> tr("Non configuré.") to Ui.GRAY
            HaClient.Status.CONNECTING -> tr("Connexion en cours…") to Ui.ORANGE
            HaClient.Status.CONNECTED -> tr("Connecté à Home Assistant.") to Ui.OK
            HaClient.Status.AUTH_FAILED -> tr("Jeton refusé par Home Assistant.") to Ui.BAD
            HaClient.Status.DISCONNECTED -> tr("Impossible de joindre Home Assistant") + (ha.lastError?.let { " ($it)" } ?: "") + "." to Ui.BAD
        }
        statusRow.setSubtitle(txt)
        (statusRow as? LinearLayout)?.getChildAt(0)?.background = roundRect(color, dpf(9))
        statusDot.text = "● " + if (ha.demo) tr("Mode démo") else when (ha.status) {
            HaClient.Status.CONNECTED -> tr("Connecté")
            HaClient.Status.CONNECTING -> tr("Connexion…")
            HaClient.Status.IDLE -> tr("Non configuré")
            else -> tr("Hors ligne")
        }
        statusDot.setTextColor(color)
        if (::mqttRow.isInitialized) updateMqtt()
    }

    private fun saveConnection() {
        var u = urlEdit.text.toString().trim().trimEnd('/')
        // Champ vide = on garde le jeton déjà enregistré
        val t = tokenEdit.text.toString().trim().ifEmpty { cfg.token }
        if (u.isEmpty() || t.isEmpty()) { toast(tr("Indiquez l'adresse et le jeton.")); return }
        tokenEdit.setText("")
        tokenEdit.hint = tr("•••••••• jeton enregistré (laisser vide pour le garder)")
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        urlEdit.setText(u)
        cfg.url = u
        cfg.token = t
        if (cfg.demo) { cfg.demo = false; recreate() }
        app.catalog = null
        app.ha.setEntities(cfg.allEntityIds())
        app.ha.configure(u, t)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlEdit.windowToken, 0)
    }

    private fun updateMemory() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pss = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        versionText.text = "Version ${About.version(app)}"
        infoCard.removeAllViews()
        for (i in About.info(app)) infoCard.addRow(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(TextView(context).apply { text = i.label; textSize = 15.5f; setTextColor(Ui.TEXT) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = i.value; textSize = 15f; setTextColor(Ui.MUTE); gravity = Gravity.END; setTextIsSelectable(true) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f))
        })
        (memAppRow.getChildAt(memAppRow.childCount - 1) as TextView).text = "${pss / 1024} Mo"
        (memFreeRow.getChildAt(memFreeRow.childCount - 1) as TextView).text = "${mi.availMem / 1048576} Mo"
    }

    // ------------------------------------------------------------ pièces

    private fun refreshRooms() {
        roomsBox.removeAllViews()
        val rooms = cfg.rooms
        if (rooms.isEmpty()) roomsBox.addView(settingRow(R.drawable.ic_home, Ui.GRAY, tr("Aucune pièce pour l'instant."), tr("Ajoutez-en une ou importez les zones de Home Assistant.")))
        rooms.forEachIndexed { i, r ->
            // Chaque ligne = contenu + séparateur, pour que le glisser-déposer compte une vue par pièce.
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(62)
                setPadding(0, dp(6), dp(8), dp(6))
                isClickable = true
                background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))
                setOnClickListener { editRoom(r) }
            }
            row.addView(dragGrip({ wrap }) { y -> dropRoom(i, y) })
            row.addView(iconBadge(HomeText.roomIcon(r.name), Ui.ROOM_COLORS[i % Ui.ROOM_COLORS.size], 36),
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = r.name; textSize = 16.5f; setTextColor(Ui.TEXT) })
                addView(TextView(context).apply {
                    text = if (r.entities.size == 1) tr("1 appareil") else "${r.entities.size} " + tr("appareils")
                    textSize = 13.5f; setTextColor(Ui.MUTE)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(iconButton(R.drawable.ic_up) { if (i > 0) move(i, -1) }.apply { alpha = if (i > 0) 1f else 0.25f })
            row.addView(iconButton(R.drawable.ic_down) { if (i < rooms.size - 1) move(i, 1) }.apply { alpha = if (i < rooms.size - 1) 1f else 0.25f })
            row.addView(iconButton(R.drawable.ic_edit) {
                promptText(tr("Renommer la pièce"), r.name) { n -> if (n.isNotEmpty()) { r.name = n; cfg.saveRooms(); refreshRooms() } }
            })
            row.addView(iconButton(R.drawable.ic_trash, 0xCCFF6961.toInt()) {
                confirm(tr("Supprimer") + " « ${r.name} » ?", tr("La pièce sera retirée du tableau de bord (rien n'est modifié dans Home Assistant)."), tr("Supprimer")) {
                    cfg.rooms.remove(r)
                    cfg.saveRooms()
                    app.ha.setEntities(cfg.allEntityIds())
                    refreshRooms()
                }
            })
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(0x66FFFFFF)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(2) })
            wrap.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (i < rooms.size - 1) wrap.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = dp(98) })
            roomsBox.addView(wrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    /** Glisser-déposer : place la pièce i à la ligne située sous la position y. */
    private fun dropRoom(from: Int, y: Float) {
        var to = from
        for (k in 0 until roomsBox.childCount) {
            val c = roomsBox.getChildAt(k)
            if (y >= c.top && y < c.bottom) { to = k; break }
            if (k == roomsBox.childCount - 1 && y >= c.bottom) to = k
            if (k == 0 && y < c.top) to = 0
        }
        to = to.coerceIn(0, cfg.rooms.size - 1)
        if (to != from) {
            val r = cfg.rooms.removeAt(from)
            cfg.rooms.add(to, r)
            cfg.saveRooms()
        }
        refreshRooms()
    }

    private fun move(i: Int, d: Int) {
        Collections.swap(cfg.rooms, i, i + d)
        cfg.saveRooms()
        refreshRooms()
    }

    private fun editRoom(r: Room) {
        startActivity(Intent(this, RoomEditActivity::class.java).putExtra(RoomEditActivity.EXTRA_ROOM, r.id))
    }

    private fun addRoom() {
        promptText(tr("Nouvelle pièce"), "", tr("Ex. Salon")) { n ->
            if (n.isEmpty()) return@promptText
            val r = cfg.newRoom(n)
            cfg.rooms.add(r)
            cfg.saveRooms()
            refreshRooms()
            editRoom(r)
        }
    }

    private fun importAreas() {
        if (app.ha.status != HaClient.Status.CONNECTED) { toast(tr("Connectez-vous d'abord à Home Assistant.")); return }
        confirm(
            tr("Importer les zones"),
            tr("Une pièce sera créée pour chaque zone Home Assistant qui n'existe pas encore ici, avec ses lumières, prises, volets, thermostats, serrures, médias et capteurs principaux. Vous pourrez ensuite tout ajuster."),
            tr("Importer")
        ) {
            val wait = AlertDialog.Builder(this).setMessage(tr("Chargement depuis Home Assistant…")).setCancelable(false).show()
            CatalogLoader.load(app) { cat, err ->
                wait.dismiss()
                if (cat == null) { toast(tr("Échec") + " : ${err ?: tr("Erreur inconnue")}"); return@load }
                val added = CatalogLoader.importAreas(cfg, cat)
                app.ha.setEntities(cfg.allEntityIds())
                refreshRooms()
                toast(if (added == 0) tr("Aucune nouvelle zone à importer.") else "$added " + tr("pièce(s) importée(s)."))
            }
        }
    }
}
