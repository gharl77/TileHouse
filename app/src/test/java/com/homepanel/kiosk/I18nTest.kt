package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    @Test fun everyEntryIsTranslated() {
        for ((fr, en) in I18n.EN) {
            assertTrue("clé vide", fr.isNotBlank())
            assertTrue("traduction vide pour « $fr »", en.isNotBlank())
        }
    }

    @Test fun switchingLanguage() {
        I18n.apply("fr"); assertEquals("Pièces", tr("Pièces"))
        I18n.apply("en"); assertEquals("Rooms", tr("Pièces"))
        assertEquals("texte sans traduction", tr("texte sans traduction"))
        I18n.apply("fr")
    }

    /** Les réglages affichés aux trois endroits doivent tous avoir leur nom anglais. */
    @Test fun settingsLabelsExistInEnglish() {
        for (fr in listOf("Affichage", "Sons et alertes", "Connexion", "Sécurité", "Skin de nuit", "Luminosité", "Écran de veille", "Heures calmes"))
            assertTrue("« $fr » n'a pas de traduction", I18n.EN.containsKey(fr))
    }
}
