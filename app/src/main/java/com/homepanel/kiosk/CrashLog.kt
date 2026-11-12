package com.homepanel.kiosk

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Journal du dernier plantage. Une tablette murale tourne sans surveillance : si l'app se ferme toute seule,
 * elle redémarre et personne ne le voit. On garde donc la dernière erreur fatale (date, version, résumé et trace)
 * pour l'afficher dans « À propos », sur la page web et dans Home Assistant.
 */
object CrashLog {
    class Entry(val time: Long, val version: String, val summary: String, val trace: String)

    /** Moment où l'application a démarré (capteur « Dernier démarrage »). */
    val startedAt = System.currentTimeMillis()

    /** Erreurs de la bibliothèque réseau ignorées depuis le démarrage (voir App.guardNetworkThreads). */
    @Volatile var ignored = 0
        private set
    fun countIgnored() { ignored++ }

    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    /** Enregistre l'erreur fatale. Appelé juste avant que l'app se ferme : ne doit jamais lever d'erreur lui-même. */
    fun record(ctx: Context, thread: Thread, e: Throwable, version: String) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val summary = (e.javaClass.simpleName.ifEmpty { e.javaClass.name } + (e.message?.let { " : $it" } ?: "")).replace('\n', ' ').take(160)
            file(ctx).writeText(System.currentTimeMillis().toString() + "\n" + version + "\n" + summary + "\n" +
                "Thread: " + thread.name + "\n" + sw.toString().take(12_000))
        } catch (_: Throwable) {}
    }

    fun last(ctx: Context): Entry? = try {
        val f = file(ctx)
        if (f.length() <= 0) null else f.readText().split("\n", limit = 4).let { p ->
            if (p.size < 4) null else Entry(p[0].toLong(), p[1], p[2], p[3])
        }
    } catch (_: Throwable) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }
}
