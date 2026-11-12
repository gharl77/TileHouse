package com.homepanel.kiosk

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Copies automatiques de la configuration : une par semaine, sans les secrets (jeton, mots de passe, PIN),
 * gardées sur la tablette et téléchargeables depuis la page web. Les six dernières sont conservées.
 */
object Backups {
    private const val KEEP = 6
    private const val EVERY = 7 * 86_400_000L
    private val NAME = Regex("tilehouse-config-\\d{4}-\\d{2}-\\d{2}\\.json")

    private fun dir(ctx: Context) = File(ctx.filesDir, "backups").apply { mkdirs() }

    /** Copies, la plus récente d'abord. */
    fun list(ctx: Context): List<File> = (dir(ctx).listFiles() ?: emptyArray()).filter { NAME.matches(it.name) }.sortedByDescending { it.name }

    /** Faut-il une nouvelle copie ? [newest] : date de la plus récente (0 s'il n'y en a pas). */
    fun due(newest: Long, now: Long) = now - newest >= EVERY

    /** Fait une copie si la dernière a plus d'une semaine ([force] : maintenant). Retourne le fichier écrit, ou null. */
    fun run(app: App, force: Boolean = false): File? {
        val cfg = app.config
        if (cfg.demo || !cfg.isConfigured) return null
        val now = System.currentTimeMillis()
        if (!force && !due(list(app).firstOrNull()?.lastModified() ?: 0L, now)) return null
        return try {
            val f = File(dir(app), "tilehouse-config-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)) + ".json")
            f.writeText(cfg.exportJson(false).toString(2))
            for (old in list(app).drop(KEEP)) old.delete()
            f
        } catch (_: Exception) { null }
    }

    /** Contenu d'une copie ; le nom est vérifié pour ne jamais lire un autre fichier. */
    fun read(ctx: Context, name: String): String? = if (!NAME.matches(name)) null else try { File(dir(ctx), name).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
}
