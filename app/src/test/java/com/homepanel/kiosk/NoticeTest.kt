package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTest {
    @Test fun plainText() {
        val n = Notice.parse("  La laveuse a terminé  ")
        assertEquals("La laveuse a terminé", n.message)
        assertEquals("", n.title)
        assertEquals(30, n.duration)
        assertFalse(n.sound)
        assertFalse(n.isUrgent)
    }

    @Test fun json() {
        val n = Notice.parse("""{"title":"Porte","message":"Porte avant déverrouillée","color":"rouge","duration":90,"sound":true,"tag":" porte ",
            "actions":[{"title":"Verrouiller","service":"lock.lock","entity_id":"lock.avant"},{"title":"","service":"lock.lock"},
                       {"title":"Sans service","service":"rien"},{"title":"A","service":"a.b"},{"title":"B","service":"a.b"}]}""")
        assertEquals("Porte", n.title)
        assertEquals("Porte avant déverrouillée", n.message)
        assertEquals(90, n.duration)
        assertTrue(n.sound)
        assertEquals("porte", n.tag)
        assertTrue("le rouge est une alerte", n.isUrgent)
        // Trois boutons au plus sont lus, et seuls ceux qui ont un titre et un service valide sont gardés.
        assertEquals(listOf("Verrouiller"), n.actions.map { it.optString("title") })
    }

    @Test fun durationIsBounded() {
        assertEquals(3600, Notice.parse("""{"message":"x","duration":99999}""").duration)
        assertEquals(0, Notice.parse("""{"message":"x","duration":-5}""").duration)
    }

    @Test fun clearRequest() {
        assertTrue(Notice.parse("""{"message":"clear_notification","tag":"leon"}""").isClear)
        assertTrue(Notice.parse("CLEAR_NOTIFICATION").isClear)
        assertFalse(Notice.parse("Bonjour").isClear)
    }

    @Test fun brokenJsonIsShownAsText() {
        assertEquals("{pas du json", Notice.parse("{pas du json").message)
    }

    @Test fun remoteImagesAreRefused() {
        assertEquals(null, Notice.parse("""{"message":"x","image":"https://exemple.com/a.jpg"}""").image)
        assertEquals("camera.salon", Notice.parse("""{"message":"x","image":"camera.salon"}""").image)
    }
}
