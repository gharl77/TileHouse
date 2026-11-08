package com.homepanel.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Petits utilitaires pour construire les écrans de réglages sans XML. */

fun Activity.sectionTitle(text: String) = TextView(this).apply {
    this.text = text
    textSize = 20f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setPadding(0, dp(28), 0, dp(8))
}

fun Activity.note(text: String) = TextView(this).apply {
    this.text = text
    textSize = 14f
    alpha = 0.75f
    setPadding(0, dp(4), 0, dp(4))
}

fun Activity.field(hint: String, value: String, type: Int) = EditText(this).apply {
    this.hint = hint
    // L'ordre compte : « une seule ligne » remplace l'affichage en points des mots de passe.
    isSingleLine = true
    inputType = type
    // La variante n'a de sens qu'avec la classe (texte « adresse web » = nombre « mot de passe » = 0x10).
    val cls = type and android.text.InputType.TYPE_MASK_CLASS
    val variation = type and android.text.InputType.TYPE_MASK_VARIATION
    val secret = (cls == android.text.InputType.TYPE_CLASS_TEXT && variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
        (cls == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    if (secret) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
    setText(value)
}

fun Activity.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Activity.flatButton(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
    this.text = text
    isAllCaps = false
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(10), 0, dp(10), 0)
    isFocusable = false
    setOnClickListener { onClick() }
}

fun Activity.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit) = Spinner(this).apply {
    adapter = ArrayAdapter(this@spinner, android.R.layout.simple_spinner_dropdown_item, items)
    setSelection(selected.coerceIn(0, maxOf(0, items.size - 1)), false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

fun Activity.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

fun Activity.promptText(title: String, initial: String, hint: String = "", onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial)
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSelection(text.length)
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) })
        .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
        .setNegativeButton(tr("Annuler"), null)
        .show()
}

fun Activity.confirm(title: String, message: String, ok: String, onOk: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .setNegativeButton(tr("Annuler"), null)
        .show()
}

/**
 * Poignée « ≡ » : la ligne suit le doigt verticalement ; au relâchement, onDrop reçoit
 * la position verticale du centre de la ligne (coordonnées de son parent).
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
fun Activity.dragGrip(row: () -> View, onDrop: (centerY: Float) -> Unit) = TextView(this).apply {
    text = "≡"
    textSize = 26f
    alpha = 0.6f
    setPadding(dp(8), dp(4), dp(14), dp(4))
    var startY = 0f
    setOnTouchListener { v, ev ->
        val r = row()
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startY = ev.rawY
                v.parent?.requestDisallowInterceptTouchEvent(true)
                r.alpha = 0.7f
                r.elevation = dp(8).toFloat()
                r.setBackgroundColor(0x33FFFFFF)
            }
            android.view.MotionEvent.ACTION_MOVE -> r.translationY = ev.rawY - startY
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val center = r.top + r.translationY + r.height / 2f
                r.translationY = 0f
                r.alpha = 1f
                r.elevation = 0f
                r.background = null
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) onDrop(center)
            }
        }
        true
    }
}

fun hrow(activity: Activity, vararg views: View) = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    for (v in views) addView(v)
}
