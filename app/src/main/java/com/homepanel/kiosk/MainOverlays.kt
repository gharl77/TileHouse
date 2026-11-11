package com.homepanel.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Ce qui s'affiche par-dessus l'écran : minuteries, carte de notification, centre de notifications.
 * Fonctions de [MainActivity] regroupées par fonctionnalité ; l'état reste dans la classe.
 */

internal fun MainActivity.openTimers() {
    val p = timerPanel ?: TimerPanel(this).also {
        root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        timerPanel = it
    }
    p.show()
}

/** Fin d'une minuterie : grand encadré jusqu'à OK (la sonnerie en boucle est gérée par l'application). */
internal fun MainActivity.showTimerDone(t: KitchenTimer) {
    if (dimmed) undim()
    lastTouch = SystemClock.uptimeMillis()
    timerPanel?.hide()
    morningPanel?.hide()
    center?.hide()
    noticeCard().show(Notice(tr("Minuterie terminée !"), tr("Minuterie de") + " " + t.label, "⏰", 0, 0xFFFF9F0A.toInt(), null, false),
        0) { app.stopTimerAlarm() }
}

internal fun MainActivity.noticeCard(): NotificationCard = notice ?: NotificationCard(this).also {
    root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    it.onClosed = { lastTouch = SystemClock.uptimeMillis(); app.stopTimerAlarm() }
    notice = it
}

/**
 * Nouvelle notification de HA : encadré centré au plus [Config.noticeSeconds] secondes
 * (moins si l'automatisation le demande). OK = lue ; sinon elle reste dans la clochette.
 */
internal fun MainActivity.showNotice(s: StoredNotice) {
    if (dimmed) undim()
    lastTouch = SystemClock.uptimeMillis()
    val max = cfg.noticeSeconds
    val d = s.notice.duration
    noticeCard().show(s.notice, if (d in 1 until max) d else max) { app.notices.markRead(s.id) }
}

/** Ouverte depuis le centre de notifications : elle devient lue tout de suite. */
internal fun MainActivity.openNotice(s: StoredNotice) {
    lastTouch = SystemClock.uptimeMillis()
    // Afficher d'abord : marquer comme lue supprime la capture enregistrée de l'alerte caméra.
    noticeCard().show(s.notice.copyNoSound(), cfg.noticeSeconds)
    app.notices.markRead(s.id)
}

internal fun MainActivity.openCenter() {
    val c = center ?: NotificationCenter(this, app.notices) { openNotice(it) }.also {
        root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        center = it
    }
    c.show()
}
