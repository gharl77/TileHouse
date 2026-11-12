package com.homepanel.kiosk

/**
 * Choix par défaut des listes « tout est coché, sauf… » des réglages : certains appareils sont décochés d'office
 * parce qu'ils n'ont presque jamais leur place (porte du réfrigérateur dans « portes ouvertes », assistant vocal
 * dans « En lecture »). L'utilisateur peut les recocher ; son choix passe toujours avant la règle.
 */
object Choices {
    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private val APPLIANCE = Regex("refrigerat|frigo|fridge|congelat|freezer|laveuse|washer|washing|secheuse|dryer|lave.vaisselle|dishwasher|oven|micro.?onde|microwave")
    private val LOCK = Regex("serrure|lock|status of the door")
    private val VOICE = Regex("voice|assist|satellite|atom.?echo|m5stack|respeaker")

    /** Porte qui ne dit rien sur la maison : celle d'un électroménager, ou l'état « porte » que publie une serrure (en double du capteur de porte). */
    fun summaryHiddenByDefault(id: String, name: String, kind: String?): Boolean {
        if (kind != "door") return false
        val t = norm("$id $name")
        return APPLIANCE.containsMatchIn(t) || LOCK.containsMatchIn(t)
    }

    /** Assistant vocal : il « joue » à chaque réponse, sans que ce soit de la musique ou une émission. */
    fun playerHiddenByDefault(id: String, name: String) = VOICE.containsMatchIn(norm("$id $name"))

    /** Décoché ? [hidden] et [shown] sont les choix explicites ; sans choix, la règle par défaut [auto] s'applique. */
    fun hidden(id: String, hidden: Collection<String>, shown: Collection<String>, auto: Boolean) = id in hidden || (auto && id !in shown)
}
