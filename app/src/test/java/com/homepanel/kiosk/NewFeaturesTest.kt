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
        fun p(id: String, state: String) = id to EntityState(id, state, org.json.JSONObject().put("mass_player_type", "player").put("app_id", "music_assistant"))
        // Une enceinte de Music Assistant qui joue de l'AirPlay : en lecture, mais pas notre musique.
        val airplay = EntityState("media_player.s", "playing", org.json.JSONObject().put("mass_player_type", "player").put("app_id", "airplay").put("source", "AirPlay"))
        assertFalse(Music.active(airplay)); assertTrue(Music.busyElsewhere(airplay)); assertFalse(Music.fromAssistant(airplay))
        assertTrue(Music.active(EntityState("media_player.s", "playing", org.json.JSONObject().put("source", "Music Assistant Queue"))))
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

    @Test fun postersFromWhatIsPlaying() {
        assertEquals("dQw4w9WgXcQ", Posters.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3"))
        assertEquals("dQw4w9WgXcQ", Posters.youtubeId("https://youtu.be/dQw4w9WgXcQ")); assertEquals("dQw4w9WgXcQ", Posters.youtubeId("dQw4w9WgXcQ"))
        assertNull(Posters.youtubeId("library://track/2510")); assertNull(Posters.youtubeId(""))
        assertEquals("https://i.ytimg.com/vi/abc/hq720.jpg", Posters.youtubeThumb("abc"))
        assertEquals("https://static.tvmaze.com/a.jpg", Posters.fromTvmaze("""{"name":"X","image":{"medium":"http://static.tvmaze.com/a.jpg"}}"""))
        assertEquals("", Posters.fromTvmaze("""{"name":"X","image":null}""")); assertEquals("", Posters.fromTvmaze(null))
        assertEquals("https://x/600x600bb.jpg", Posters.fromItunes("""{"results":[{"artworkUrl100":"https://x/100x100bb.jpg"}]}""")); assertEquals("", Posters.fromItunes("""{"results":[]}"""))
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", Posters.fromTmdb("""{"results":[{"poster_path":null},{"poster_path":"/p.jpg"}]}"""))
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", Posters.fromTmdb("""{"results":[{"poster_path":"/p.jpg","backdrop_path":"/b.jpg"}]}"""))
        assertEquals("https://i/h.jpg", Posters.fromYoutube("""{"items":[{"snippet":{"thumbnails":{"default":{"url":"https://i/d.jpg"},"high":{"url":"https://i/h.jpg"}}}}]}"""))
        assertEquals("https://i.ytimg.com/vi/tgkNG5nPYXk/hq720.jpg", Posters.fromYoutubePage("""x{"videoRenderer":{"videoId":"tgkNG5nPYXk","thumbnail":{}}}"""))
        assertEquals("https://i.ytimg.com/vi/tgkNG5nPYXk/hq720.jpg", Posters.fromYoutubePage("a videoId\\x22:\\x22tgkNG5nPYXk b")); assertEquals("", Posters.fromYoutubePage("rien"))
        assertEquals("https://i.ytimg.com/vi/tgkNG5nPYXk/hq720.jpg", Posters.fromYoutube("""{"items":[{"id":{"videoId":"tgkNG5nPYXk"},"snippet":{"thumbnails":{"high":{"url":"https://i/h.jpg"}}}}]}"""))
        fun st(vararg kv: Pair<String, Any>) = EntityState("media_player.tv", "playing", org.json.JSONObject().apply { for ((k, v) in kv) put(k, v) })
        // Une série : on cherche la série, pas le titre de l'épisode. De la musique : l'artiste avec la chanson.
        assertEquals("District 31", Posters.what(st("media_title" to "Épisode 12", "media_series_title" to "District 31", "media_content_type" to "tvshow")).term)
        assertEquals("LIVE The Dolphin's Cry", Posters.what(st("media_title" to "The Dolphin's Cry", "media_artist" to "LIVE", "media_content_type" to "music")).term)
        assertTrue(Posters.canTurnOff(st("supported_features" to 450487))); assertFalse(Posters.canTurnOff(st("supported_features" to 152461 - 256))); assertFalse(Posters.canTurnOff(st()))
        // Apple TV : pas de type de contenu, mais une application qui n'est pas de la musique = vidéo.
        assertTrue(Posters.what(st("media_title" to "x", "app_name" to "ICI TOU.TV", "app_id" to "ca.radio-canada.toutv")).video)
        assertFalse(Posters.what(st("media_title" to "x", "app_name" to "Music", "app_id" to "com.apple.TVMusic")).video)
        assertEquals("Titre Chaîne", Posters.what(st("media_title" to "Titre", "media_artist" to "Chaîne", "app_name" to "YouTube", "app_id" to "com.google.ios.youtube")).term)
        assertTrue(Posters.what(st("media_title" to "x", "app_name" to "YouTube")).youtube); assertFalse(Posters.what(st("media_title" to "x", "app_name" to "Tou.tv")).youtube)
    }

    @Test fun liveTvGivesTheChannelAndTriesTheSeriesAlone() {
        val fibe = EntityState("media_player.tv", "playing", org.json.JSONObject().put("media_title", "Tatoué Rodéo - Ayer's Cliff").put("media_content_id", "RDSHD")
            .put("media_content_type", "video").put("app_name", "Télé Fibe").put("app_id", "CA.BELL.FIBEREMOTE"))
        val w = Posters.what(fibe)
        assertEquals(listOf("Tatoué Rodéo - Ayer's Cliff", "Tatoué Rodéo"), w.terms)
        assertEquals("RDS", w.channel); assertTrue(Channels.logo("RDSHD").endsWith("/canada/rds-ca.png")); assertTrue(Channels.isLogo(Channels.logo("RDSHD")))
        assertEquals("RDS 2", Channels.name("RDS2HD")); assertEquals("TVA Sports", Channels.name("TVASP"))
        // Code inconnu : on garde le code comme nom et on tente le fichier du même nom.
        assertEquals("ZZTV", Channels.name("ZZTVHD")); assertTrue(Channels.logo("ZZTVHD").endsWith("/zztv-ca.png"))
        assertEquals("Noovo", Channels.name("NVQB")); assertEquals("TVA", Channels.name("TVAQH"))
        // Nom et numéro saisis dans les réglages : ils passent avant la table, et le numéro s'affiche.
        val before = Channels.custom
        Channels.custom = { org.json.JSONObject("""{"RDS":{"name":"","number":"1125"},"TVAQH":{"name":"TVA Québec","number":"1104"}}""") }
        assertEquals("RDS  ·  chaîne 1125", Channels.label("RDSHD").replace("channel", "chaîne")); assertEquals("TVA Québec", Channels.name("TVAQH")); assertEquals("", Channels.number("NVQB"))
        Channels.custom = before
        assertEquals("RDS", Channels.label("RDSHD"))
        // Pas des chaînes : une adresse, un identifiant YouTube, une piste de bibliothèque.
        assertEquals("", Channels.name("library://track/2510")); assertEquals("", Channels.name("dQw4w9WgXcQ")); assertEquals("", Channels.logo(""))
        assertEquals(listOf("Le sport en toute liberté"), Posters.what(EntityState("media_player.tv", "playing", org.json.JSONObject().put("media_title", "Le sport en toute liberté").put("media_content_type", "video"))).terms)
    }

    @Test fun scoresFromATeamTrackerSensor() {
        fun sensor(state: String, vararg kv: Pair<String, Any>) = EntityState("sensor.mtl", state, org.json.JSONObject().apply {
            put("team_abbr", "MTL"); put("team_name", "Canadiens"); put("team_logo", "https://l/mtl.png"); put("team_score", "2"); put("team_homeaway", "home")
            put("opponent_abbr", "COL"); put("opponent_name", "Avalanche"); put("opponent_logo", "https://l/col.png"); put("opponent_score", "1")
            put("date", "2026-11-15T00:00Z"); put("league", "NHL"); put("clock", "12:34 - 2nd")
            for ((k, v) in kv) put(k, v)
        })
        val start = 1794700800000L // 2026-11-15T00:00Z
        val live = Scores.game(sensor("IN"))!!
        // Visiteur à gauche, équipe locale à droite.
        assertEquals("COL", live.away.abbr); assertEquals("MTL", live.home.abbr); assertEquals("1 – 2", Scores.score(live)); assertEquals(start, live.startsAt)
        assertTrue(Scores.line(live).startsWith("COL 1 – 2 MTL")); assertTrue(Scores.line(live).contains("12:34 - 2nd"))
        assertEquals("@", Scores.score(Scores.game(sensor("PRE"))!!)); assertNull(Scores.game(sensor("NOT_FOUND"))); assertNull(Scores.game(null))
        // À venir : montré 12 h avant ; terminé : encore 8 h ; en cours : toujours.
        val pre = Scores.game(sensor("PRE"))!!; val post = Scores.game(sensor("POST"))!!
        assertTrue(Scores.visible(pre, start - 2 * 3_600_000L)); assertFalse(Scores.visible(pre, start - 20 * 3_600_000L))
        assertTrue(Scores.visible(post, start + 4 * 3_600_000L)); assertFalse(Scores.visible(post, start + 20 * 3_600_000L)); assertTrue(Scores.visible(live, 0))
        // L'équipe suivie joue à l'étranger : elle passe à gauche.
        assertEquals("MTL", Scores.game(sensor("IN", "team_homeaway" to "away"))!!.away.abbr)
        assertEquals(1, Scores.active(listOf("a", "b"), mapOf("a" to sensor("IN"), "b" to sensor("IN")), 0).size)
    }

    @Test fun twoTeamsOfTheSameLeagueGiveTheirLogos() {
        fun league(name: String, vararg t: Pair<String, String>) = Sports.parse(org.json.JSONObject().put("sports", org.json.JSONArray().put(org.json.JSONObject().put("leagues",
            org.json.JSONArray().put(org.json.JSONObject().put("teams", org.json.JSONArray().apply {
                for ((nick, full) in t) put(org.json.JSONObject().put("team", org.json.JSONObject().put("name", nick).put("displayName", full)
                    .put("logos", org.json.JSONArray().put(org.json.JSONObject().put("href", "https://l/$nick.png")))))
            }))))).toString(), name)
        val all = league("nhl", "Canadiens" to "Montreal Canadiens", "Rangers" to "New York Rangers", "Avalanche" to "Colorado Avalanche", "Senators" to "Ottawa Senators", "Stars" to "Dallas Stars") +
            league("mlb", "Rangers" to "Texas Rangers", "Blue Jays" to "Toronto Blue Jays")
        fun logos(title: String) = Sports.match(title, all)?.let { it.first.logo.substringAfterLast('/') to it.second.logo.substringAfterLast('/') }
        assertEquals("Avalanche.png" to "Canadiens.png", logos("Avalanche @ Canadiens"))
        assertEquals("Canadiens.png" to "Rangers.png", logos("Hockey LNH : Canadiens c. Rangers"))
        assertEquals("Senators.png" to "Canadiens.png", logos("Sénateurs d'Ottawa contre le Canadien de Montréal"))
        // Rangers existe dans deux ligues : l'autre équipe du titre dit laquelle.
        assertEquals("https://l/Rangers.png", Sports.match("Blue Jays - Rangers", all)!!.second.logo); assertEquals("mlb", Sports.match("Blue Jays - Rangers", all)!!.second.league)
        // Une seule équipe, ou un mot courant : pas de logos.
        assertNull(logos("Les Canadiens en rafale")); assertNull(logos("Dancing with the Stars")); assertNull(logos("Le sport en toute liberté"))
        assertTrue(Sports.pairUrl(all[0], all[1]).startsWith("teams:https://l/Canadiens.png|"))
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
