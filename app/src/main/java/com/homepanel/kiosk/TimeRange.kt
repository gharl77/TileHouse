package com.homepanel.kiosk

/** Plages horaires en minutes depuis minuit (heures calmes, « Ce matin », skin de nuit). */
object TimeRange {
    /** Vrai si [minute] est dans la plage [start, end[ ; la plage peut passer minuit ; début = fin → plage vide. */
    fun contains(start: Int, end: Int, minute: Int): Boolean =
        if (start == end) false else if (start < end) minute in start until end else minute >= start || minute < end
}
