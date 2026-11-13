package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun aisleOfCommonItems() {
        assertEquals("🥛  Produits laitiers et œufs", Aisles.of("Lait 2 %"))
        assertEquals("🥛  Produits laitiers et œufs", Aisles.of("Œufs"))
        assertEquals("🥕  Fruits et légumes", Aisles.of("Laitue romaine"))
        assertEquals("🥫  Épicerie", Aisles.of("Beurre d'arachide"))
        assertEquals("🥤  Boissons", Aisles.of("Jus d'orange"))
        assertEquals("🥖  Boulangerie", Aisles.of("Bread"))
        assertNull(Aisles.of("Appeler le plombier"))
        assertNull(Aisles.of("Balai"))
    }

    @Test fun groceryListIsGroupedInStoreOrder() {
        val g = Aisles.group(listOf("Pain", "Lait", "Pommes", "Truc bizarre", "Fromage")) { it }!!
        assertEquals(listOf("🥕  Fruits et légumes", "🥛  Produits laitiers et œufs", "🥖  Boulangerie", Aisles.OTHER), g.map { it.first })
        assertEquals(listOf("Lait", "Fromage"), g[1].second)
    }

    @Test fun taskListIsNotGrouped() {
        assertNull(Aisles.group(listOf("Appeler le plombier", "Payer les taxes", "Laver l'auto", "Acheter du lait")) { it })
        assertNull(Aisles.group(listOf("Lait", "Pain")) { it })
    }

    @Test fun nightFollowsSunriseAndSunset() {
        // Lever à 7 h, coucher à 16 h (UTC, pour le calcul) : le prochain lever est demain, le prochain coucher aussi.
        val rising = 10 * day + 7 * hour; val setting = 10 * day + 16 * hour
        assertTrue(HomeText.isNight(9 * day + 23 * hour, rising, setting))
        assertTrue(HomeText.isNight(10 * day + 3 * hour, rising, setting))
        assertFalse(HomeText.isNight(10 * day + 12 * hour, rising, setting))
        assertTrue(HomeText.isNight(10 * day + 18 * hour, rising, setting))
        // En plein jour : le prochain coucher vient avant le prochain lever.
        assertFalse(HomeText.isNight(10 * day + 9 * hour, 11 * day + 7 * hour, 10 * day + 16 * hour))
        assertFalse(HomeText.isNight(0, null, null))
    }

    @Test fun nightIconReplacesTheSun() {
        assertEquals("🌙", HomeText.emoji("sunny", night = true))
        assertEquals("☀️", HomeText.emoji("sunny"))
        assertEquals("🌧️", HomeText.emoji("rainy", night = true))
    }

    @Test fun remindersRoundTripAndExpire() {
        val list = Reminders.parse("""[{"key":"k","title":"Dentiste","at":1000,"start":5000,"allDay":false},{"at":0},{"bad":1}]""")
        assertEquals(1, list.size)
        assertEquals("Dentiste", list[0].title)
        assertTrue(Reminders.fresh(list[0], 5000 + hour - 1))
        assertFalse(Reminders.fresh(list[0], 5000 + hour + 1))
        assertEquals(emptyList<Reminders.Reminder>(), Reminders.parse("pas du json"))
        assertEquals("5000|Dentiste", Reminders.keyOf("Dentiste", 5000))
    }

    @Test fun historyIsParsed() {
        val pts = Energy.series("""[[{"entity_id":"sensor.x","state":"1.5","last_changed":"2026-11-11T10:00:00.123456+00:00"},
            {"state":"unavailable","last_changed":"2026-11-11T10:30:00+00:00"},{"state":"2","last_changed":"2026-11-11T11:00:00+00:00"}]]""")
        assertEquals(2, pts.size)
        assertEquals(1.5, pts[0].v, 0.0)
        assertEquals(hour, pts[1].t - pts[0].t)
        assertEquals(0, Energy.series("[]").size)
        assertEquals(0, Energy.series(null).size)
    }

    @Test fun hourlyConsumptionFromACounter() {
        val pts = listOf(Energy.Point(0, 100.0), Energy.Point(hour + 10, 101.5), Energy.Point(2 * hour + 10, 103.0), Energy.Point(2 * hour + 20, 2.0))
        val h = Energy.hourly(pts, hour, 3 * hour)
        assertEquals(2, h.size)
        assertEquals(1.5, h[0].v, 1e-9)
        // Remise à zéro du compteur : pas de consommation négative.
        assertEquals(0.0, h[1].v, 1e-9)
    }

    @Test fun shortcutGoesToItsRoomOrHome() {
        val salon = Room("r1", "Salon", "living"); val cuisine = Room("r2", "Cuisine", "kitchen")
        val rooms = listOf(salon, cuisine)
        // Automatique : la pièce de la zone Home Assistant, sinon l'accueil.
        assertEquals(salon, Shortcuts.roomFor(rooms, "", "living"))
        assertNull(Shortcuts.roomFor(rooms, "", null))
        assertNull(Shortcuts.roomFor(rooms, "", "garage"))
        // Choix dans les réglages : il passe avant la zone.
        assertEquals(cuisine, Shortcuts.roomFor(rooms, "r2", "living"))
        assertNull(Shortcuts.roomFor(rooms, Shortcuts.HOME, "living"))
        // Pièce supprimée depuis : retour à l'automatique.
        assertEquals(salon, Shortcuts.roomFor(rooms, "disparue", "living"))
    }

    @Test fun aRecentPauseKeepsTheCard() {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", java.util.Locale.US)
        val now = 1_800_000_000_000L
        assertTrue(HomeText.pausedRecently(iso.format(java.util.Date(now - 5 * 60_000L)), now))
        assertFalse(HomeText.pausedRecently(iso.format(java.util.Date(now - 11 * 60_000L)), now))
        assertFalse(HomeText.pausedRecently("", now))
        assertFalse(HomeText.pausedRecently("n'importe quoi", now))
    }

    @Test fun applianceAndLockDoorsAreLeftOutByDefault() {
        assertTrue(Choices.summaryHiddenByDefault("binary_sensor.frigo_porte", "Réfrigérateur Porte du congélateur", "door"))
        assertTrue(Choices.summaryHiddenByDefault("binary_sensor.laveuse_porte", "Porte Laveuse", "door"))
        assertTrue(Choices.summaryHiddenByDefault("binary_sensor.x", "Serrure entrée avant Current status of the door", "door"))
        assertFalse(Choices.summaryHiddenByDefault("binary_sensor.porte_entree_avant", "Porte Entrée Avant", "door"))
        assertFalse(Choices.summaryHiddenByDefault("binary_sensor.porte_patio", "Porte-Patio", "door"))
        // Seules les portes sont concernées : une serrure reste une serrure.
        assertFalse(Choices.summaryHiddenByDefault("lock.serrure_entree_avant", "Serrure entrée avant", "lock"))
    }

    @Test fun voiceAssistantsMakeNoCardByDefault() {
        assertTrue(Choices.playerHiddenByDefault("media_player.ha_voice_salon", "HA Voice Salon Media Player"))
        assertTrue(Choices.playerHiddenByDefault("media_player.bureau_m5stack_atom_echo_0aeccc", "HA Voice Parents"))
        assertFalse(Choices.playerHiddenByDefault("media_player.salon_2", "SONOS Salon"))
        assertFalse(Choices.playerHiddenByDefault("media_player.parents", "Apple TV Chambre Parents"))
    }

    @Test fun anExplicitChoiceBeatsTheDefault() {
        assertTrue(Choices.hidden("a", emptyList(), emptyList(), auto = true))
        assertFalse(Choices.hidden("a", emptyList(), listOf("a"), auto = true))
        assertTrue(Choices.hidden("a", listOf("a"), emptyList(), auto = false))
        assertFalse(Choices.hidden("a", emptyList(), emptyList(), auto = false))
    }

    @Test fun blindsAndCurtainsHaveTheirOwnChip() {
        fun cover(dc: String?) = EntityState("cover.x", "open", org.json.JSONObject().apply { if (dc != null) put("device_class", dc) })
        assertEquals("cover", Present.chipKind("cover.x", cover("blind")))
        assertEquals("cover", Present.chipKind("cover.x", cover(null)))
        assertNull(Present.chipKind("cover.x", cover("garage")))
    }

    @Test fun musicLibraryItemsAndTargets() {
        val arr = org.json.JSONArray("""[{"media_type":"album","uri":"library://album/1","name":"Kind of Blue","image":null,"artists":[{"name":"Miles Davis"}]},
            {"media_type":"radio","uri":"library://radio/2","name":"ICI Musique","image":"https://x/y.jpg"},{"media_type":"track","uri":"","name":"sans adresse"}]""")
        val items = Music.items(arr)
        assertEquals(2, items.size)
        assertEquals("Miles Davis", items[0].sub); assertEquals("", items[0].image)
        assertEquals("https://x/y.jpg", items[1].image)
        assertEquals(1, Music.searchItems(org.json.JSONObject("""{"tracks":[],"radio":[{"media_type":"radio","uri":"a://b","name":"R"}]}""")).size)
        fun p(id: String, state: String) = id to EntityState(id, state, org.json.JSONObject().put("mass_player_type", "player"))
        val states = mapOf(p("media_player.a", "idle"), p("media_player.b", "playing"), p("media_player.c", "unavailable"))
        val players = listOf("media_player.a", "media_player.b", "media_player.c")
        // La cible ne joue pas : on montre l'enceinte qui joue ; rien ne joue : la cible.
        assertEquals("media_player.b", Music.current(listOf("media_player.a"), players, states))
        assertEquals("media_player.a", Music.current(listOf("media_player.a"), players, mapOf(p("media_player.a", "idle"))))
        // Transfert : ni l'enceinte qui joue, ni celle qui est hors ligne.
        assertEquals(listOf("media_player.a"), Music.transferTargets("media_player.b", players, states))
        assertEquals("all", Music.nextRepeat("off")); assertEquals("one", Music.nextRepeat("all")); assertEquals("off", Music.nextRepeat("one"))
        val up = Music.nextUp(org.json.JSONObject("""{"items":442,"current_index":2,"next_item":{"name":"LIVE - Lightning Crashes"}}"""))
        assertTrue(up.contains("LIVE - Lightning Crashes") && up.contains("3") && up.contains("442"))
        assertEquals("", Music.nextUp(org.json.JSONObject("""{"items":0,"current_index":null,"next_item":null}""")))
        assertEquals("3:07", Music.clock(187)); assertEquals("1:02:05", Music.clock(3725))
        assertEquals("http://192.168.1.5:8095", Music.serverUrl("", "http://192.168.1.5:8123")); assertEquals("http://ma:1", Music.serverUrl("http://ma:1/", "http://x:8123"))
        val paused = EntityState("media_player.a", "paused", org.json.JSONObject().put("media_duration", 264).put("media_position", 30.4))
        assertEquals(30 to 264, Music.position(paused, 0L)); assertNull(Music.position(states["media_player.a"], 0L))
        assertEquals(2, Music.queueItems("""[{"queue_item_id":"a","name":"X - Y","duration":200},{"queue_item_id":"b","media_item":{"name":"Z"}},{"name":"sans id"}]""")!!.size)
        assertEquals(1, Music.queueItems("""{"result":[{"queue_item_id":"a","name":"n"}]}""")!!.size); assertNull(Music.queueItems("Authentication required"))
        assertTrue(Music.isPlayer(states["media_player.a"]!!.attrs)); assertFalse(Music.isPlayer(org.json.JSONObject()))
    }

    @Test fun todaysConsumptionFromACounter() {
        val midnight = 100L
        // Relevé une fois par jour : l'ajout du dernier relevé.
        val daily = Energy.recent(listOf(Energy.Point(10, 3027.0), Energy.Point(60, 3094.0), Energy.Point(130, 3159.0), Energy.Point(140, 3159.0)), midnight)!!
        assertEquals(65.0, daily.value, 1e-9); assertFalse(daily.sinceMidnight)
        // Suivi en continu : la hausse depuis minuit.
        val live = Energy.recent(listOf(Energy.Point(90, 10.0), Energy.Point(110, 11.0), Energy.Point(120, 12.5), Energy.Point(130, 14.0)), midnight)!!
        assertEquals(4.0, live.value, 1e-9); assertTrue(live.sinceMidnight)
        assertNull(Energy.recent(listOf(Energy.Point(0, 3117.0)), midnight))
        assertEquals("42 kWh", Energy.format(42.0, "kWh").replace('\u00a0', ' '))
    }

    @Test fun dailyStatisticsAreParsed() {
        val arr = org.json.JSONArray("""[{"start":1794373200000,"end":1794459600000,"change":65.0},{"start":"2026-11-12T05:00:00+00:00","change":-3},{"start":1,"sum":4}]""")
        val d = Energy.daily(arr)
        assertEquals(2, d.size)
        assertEquals(65.0, d[0].v, 1e-9)
        assertEquals(1794373200000L, d[0].t)
        // Une baisse (compteur remis à zéro) ne donne pas de consommation négative.
        assertEquals(0.0, d[1].v, 1e-9)
        assertEquals(0, Energy.daily(null).size)
        assertEquals(2, Energy.distinct(listOf(Energy.Point(0, 1.0), Energy.Point(1, 1.0), Energy.Point(2, 3.0))))
    }

    @Test fun aBackupIsDueAfterAWeek() {
        val week = 7 * day
        assertTrue(Backups.due(0, 1_800_000_000_000L))
        assertFalse(Backups.due(1_800_000_000_000L - week + hour, 1_800_000_000_000L))
        assertTrue(Backups.due(1_800_000_000_000L - week, 1_800_000_000_000L))
    }
}
