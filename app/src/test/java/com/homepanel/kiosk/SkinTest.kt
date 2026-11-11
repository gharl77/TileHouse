package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Chaque skin doit rester lisible : c'est ce qui casse le plus facilement quand on en ajoute une. */
class SkinTest {
    private fun lum(c: Int): Double {
        fun ch(shift: Int): Double { val v = ((c shr shift) and 0xFF) / 255.0; return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }
    private fun contrast(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (max(x, y) + 0.05) / (min(x, y) + 0.05) }
    private fun opaque(c: Int) = (c ushr 24) >= 0xE0

    @Test fun keysAreUniqueAndNamed() {
        assertEquals(Skin.keys.size, Skin.keys.toSet().size)
        assertEquals(Skin.keys.size, Skin.names.size)
        assertTrue(Skin.names.all { it.isNotBlank() })
        assertEquals("homekit", Skin.keys.first())
    }

    @Test fun unknownKeyFallsBackToHomeKit() {
        Skin.use("n-existe-pas")
        assertEquals("homekit", Skin.key)
    }

    @Test fun textIsReadableOnPanels() {
        for (k in Skin.keys) {
            Skin.use(k)
            assertTrue("$k : texte illisible sur les fenêtres", contrast(Skin.ink, Skin.panel) >= 7.0)
            assertTrue("$k : texte du bouton principal illisible", contrast(Skin.onPrimary, Skin.primary) >= 4.5)
        }
    }

    @Test fun tileTextIsReadableOnOpaqueTiles() {
        for (k in Skin.keys) {
            Skin.use(k)
            for (active in listOf(true, false)) {
                val look = Skin.tile(active, 0xFFFFB300.toInt())
                if (opaque(look.fill)) {
                    assertTrue("$k (allumé=$active) : nom illisible", contrast(look.name, look.fill) >= 4.5)
                    assertTrue("$k (allumé=$active) : statut illisible", contrast(look.status, look.fill) >= 3.0)
                }
            }
        }
    }

    @Test fun onAndOffTilesLookDifferent() {
        for (k in Skin.keys) {
            Skin.use(k)
            val on = Skin.tile(true, 0xFFFFB300.toInt()); val off = Skin.tile(false, 0xFFFFB300.toInt())
            assertTrue("$k : une tuile allumée doit se distinguer", on.fill != off.fill || on.stroke != off.stroke || on.iconTint != off.iconTint)
        }
    }

    @Test fun sizesAreSane() {
        for (k in Skin.keys) {
            Skin.use(k)
            assertTrue(k, Skin.tileH >= Skin.iconD + 2 * 8)
            assertTrue(k, Skin.tileMinW in 120..400)
            assertTrue(k, Skin.textScale in 0.8f..1.5f)
        }
    }

    @Test fun mixBlendsChannels() {
        assertEquals(0xFF000000.toInt(), Skin.mix(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), Skin.mix(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 1f))
        assertEquals(0xFF7F7F7F.toInt(), Skin.mix(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f))
    }
}
