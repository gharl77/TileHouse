package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arrière-plan : thèmes, fonds d'écran et photo personnelle. */
class BackgroundTest {
    @Test fun seasonByMonth() {
        assertEquals("hiver", Themes.seasonFor(1)); assertEquals("hiver", Themes.seasonFor(12))
        assertEquals("printemps", Themes.seasonFor(3)); assertEquals("printemps", Themes.seasonFor(5))
        assertEquals("ete", Themes.seasonFor(6)); assertEquals("ete", Themes.seasonFor(8))
        assertEquals("automne", Themes.seasonFor(9)); assertEquals("automne", Themes.seasonFor(11))
        for (m in 1..12) assertNotNull("la saison du mois $m doit être un vrai thème", Themes.get(Themes.seasonFor(m)))
    }

    @Test fun wallpapersHaveANameEachAndIncludeThePhoto() {
        assertEquals(Wallpapers.keys.size, Wallpapers.names.size)
        assertEquals(Wallpapers.keys.size, Wallpapers.keys.toSet().size)
        assertTrue(PhotoWallpaper.KEY in Wallpapers.keys && PhotoWallpaper.IMMICH in Wallpapers.keys)
        assertTrue(PhotoWallpaper.isPhoto(PhotoWallpaper.KEY) && PhotoWallpaper.isPhoto(PhotoWallpaper.IMMICH) && !PhotoWallpaper.isPhoto("nuit"))
        assertTrue(Wallpapers.names.all { it.isNotBlank() })
    }

    @Test fun themeKeysAreUnique() {
        assertEquals(Themes.all.size, Themes.all.map { it.key }.toSet().size)
        assertTrue(Themes.all.none { it.key.isEmpty() })
    }

    /** Le voile posé sur la photo doit aller dans le sens du texte : sombre sous un texte clair, clair sous un texte foncé. */
    @Test fun photoVeilMatchesTheInk() {
        fun light(c: Int) = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 3 * 128
        for (k in Skin.keys) {
            Skin.use(k)
            assertTrue("$k : voile trop léger pour garder le texte lisible", (Skin.photoVeil ushr 24) >= 0x4D)
            assertTrue("$k : le voile doit contraster avec le texte", light(Skin.photoVeil) != light(Skin.ink))
        }
    }

    // ---- Thème automatique

    private val ca = Themes.defaultHolidays("CA")
    private fun on(y: Int, m: Int, d: Int, enabled: Set<String> = ca, country: String = "CA") =
        Themes.forDate(java.util.GregorianCalendar(y, m - 1, d), enabled, country)

    @Test fun fixedHolidays() {
        assertEquals("noel", on(2026, 12, 1)); assertEquals("noel", on(2026, 12, 25)); assertEquals("noel", on(2026, 12, 26))
        assertEquals("hiver", on(2026, 12, 27))
        assertEquals("jour_an", on(2026, 12, 31)); assertEquals("jour_an", on(2027, 1, 1)); assertEquals("hiver", on(2027, 1, 2))
        assertEquals("st_valentin", on(2026, 2, 14)); assertEquals("st_patrick", on(2026, 3, 17))
        assertEquals("st_jean", on(2026, 6, 24)); assertEquals("canada", on(2026, 7, 1))
        assertEquals("halloween", on(2026, 10, 25)); assertEquals("halloween", on(2026, 10, 31)); assertEquals("automne", on(2026, 10, 24))
    }

    @Test fun movingHolidays() {
        // Pâques 2026 : dimanche 5 avril (du Vendredi saint 3 avril au lundi 6 avril) ; 2025 : 20 avril ; 2027 : 28 mars.
        assertEquals("printemps", on(2026, 4, 2)); assertEquals("paques", on(2026, 4, 3)); assertEquals("paques", on(2026, 4, 5))
        assertEquals("paques", on(2026, 4, 6)); assertEquals("printemps", on(2026, 4, 7))
        assertEquals("paques", on(2025, 4, 20)); assertEquals("paques", on(2027, 3, 28))
        // Fête des mères : 2e dimanche de mai (10 mai 2026) ; fête des pères : 3e dimanche de juin (21 juin 2026).
        assertEquals("fete_meres", on(2026, 5, 10)); assertEquals("printemps", on(2026, 5, 3))
        assertEquals("fete_peres", on(2026, 6, 21)); assertEquals("ete", on(2026, 6, 14))
    }

    @Test fun thanksgivingDependsOnTheCountry() {
        // Canada : 2e lundi d'octobre (12 octobre 2026), avec la fin de semaine qui précède.
        assertEquals("action_grace", on(2026, 10, 10)); assertEquals("action_grace", on(2026, 10, 12)); assertEquals("automne", on(2026, 10, 13))
        // États-Unis : 4e jeudi de novembre (26 novembre 2026) et le lendemain.
        val us = Themes.defaultHolidays("US")
        assertEquals("action_grace", on(2026, 11, 26, us, "US")); assertEquals("action_grace", on(2026, 11, 27, us, "US"))
        assertEquals("automne", on(2026, 10, 12, us, "US"))
        assertEquals("independance_us", on(2026, 7, 4, us, "US")); assertEquals("ete", on(2026, 7, 4))
    }

    @Test fun onlyTickedHolidaysAreUsed() {
        assertEquals("automne", on(2026, 10, 31, emptySet()))
        assertEquals("hiver", on(2026, 12, 25, setOf("halloween")))
        assertEquals("halloween", on(2026, 10, 31, setOf("halloween")))
        for (k in Themes.AUTO_HOLIDAYS) assertNotNull("« $k » doit être un vrai thème", Themes.get(k))
        for (c in listOf("CA", "US", "FR", "")) assertTrue(Themes.defaultHolidays(c).all { it in Themes.AUTO_HOLIDAYS })
    }

    @Test fun everyDayOfTheYearGivesARealTheme() {
        val c = java.util.GregorianCalendar(2026, 0, 1)
        repeat(366) {
            assertNotNull(Themes.get(Themes.forDate(c, Themes.AUTO_HOLIDAYS.toSet(), "CA")))
            c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
    }

    // ---- Voile réglable

    @Test fun veilLevelScalesTheSkinVeil() {
        for (k in Skin.keys) {
            Skin.use(k)
            assertEquals("$k : 50 = voile de la skin", Skin.photoVeil ushr 24, Skin.photoVeil(50) ushr 24)
            assertTrue("$k : le voile doit croître avec le réglage", (Skin.photoVeil(0) ushr 24) < (Skin.photoVeil(50) ushr 24) && (Skin.photoVeil(50) ushr 24) <= (Skin.photoVeil(100) ushr 24))
            assertEquals("$k : la couleur du voile ne change pas", Skin.photoVeil and 0x00FFFFFF, Skin.photoVeil(80) and 0x00FFFFFF)
            assertTrue("$k : jamais totalement opaque", (Skin.photoVeil(100) ushr 24) < 0xFF)
        }
    }
}
