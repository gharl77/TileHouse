package com.homepanel.kiosk

import org.json.JSONObject
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
        assertFalse(Choices.summaryHiddenByDefault("lock.front_door", "Serrure entrée avant", "lock"))
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

    // ---- 2026.11.12 : contrôles dessinés, pastille de fumée

    @Test fun coverDrawingFollowsPosition() {
        assertEquals(1f, ControlMath.covered(0f, 0.07f), 0.001f)
        assertEquals(0.07f, ControlMath.covered(100f, 0.07f), 0.001f)
        assertEquals(100f, ControlMath.blindPosition(10f, 10f, 210f), 0.01f)
        assertEquals(0f, ControlMath.blindPosition(400f, 10f, 210f), 0.01f)
        assertEquals(25f, ControlMath.blindPosition(160f, 10f, 210f), 0.01f)
        // Rideau : symétrique autour du centre, fermé au centre, ouvert aux bords.
        assertEquals(0f, ControlMath.curtainPosition(100f, 0f, 200f), 0.01f)
        assertEquals(50f, ControlMath.curtainPosition(50f, 0f, 200f), 0.01f)
        assertEquals(50f, ControlMath.curtainPosition(150f, 0f, 200f), 0.01f)
        assertEquals(100f, ControlMath.curtainPosition(-20f, 0f, 200f), 0.01f)
    }

    @Test fun thermostatArcAndSteps() {
        // Plein ouest (180°) = milieu de l'arc ; à droite du cadran, rien.
        assertEquals(0.5f, ControlMath.arcFraction(-100f, 0f)!!, 0.001f)
        assertNull(ControlMath.arcFraction(100f, 0f))
        assertTrue(ControlMath.arcFraction(-60f, 80f)!! < 0.1f)   // bas à gauche : minimum
        assertTrue(ControlMath.arcFraction(-60f, -80f)!! > 0.9f)  // haut à gauche : maximum
        assertEquals(21.5, ControlMath.snap(21.4, 0.5, 5.0, 30.0), 0.0001)
        assertEquals(30.0, ControlMath.snap(44.0, 0.5, 5.0, 30.0), 0.0001)
        assertEquals(0xFFFF0000.toInt(), ControlMath.mix(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0f))
    }

    @Test fun curtainOrBlind() {
        fun cover(id: String, dc: String?, name: String = "x") =
            EntityState(id, "closed", JSONObject().put("friendly_name", name).apply { dc?.let { put("device_class", it) } })
        assertTrue(Present.isCurtain("cover.a", cover("cover.a", "curtain")))
        assertFalse(Present.isCurtain("cover.a", cover("cover.a", "shade")))
        assertTrue(Present.isCurtain("cover.rideaux_cuisine", cover("cover.rideaux_cuisine", null)))
        assertFalse(Present.isCurtain("cover.store1", cover("cover.store1", null, "Store 1")))
        val full = EntityState("cover.a", "open", JSONObject().put("supported_features", 15).put("current_position", 40))
        assertTrue(Present.coverCan(full, 8)); assertEquals(40, Present.coverPosition(full))
        val basic = EntityState("cover.b", "open", JSONObject().put("supported_features", 3))
        assertFalse(Present.coverCan(basic, 4)); assertFalse(Present.coverCan(basic, 8)); assertEquals(100, Present.coverPosition(basic))
    }

    @Test fun smokeChip() {
        fun det(id: String, dc: String, on: Boolean) = EntityState(id, if (on) "on" else "off", JSONObject().put("device_class", dc))
        val room = Room("r", "Couloir").apply { entities.add(RoomEntity("binary_sensor.s")); entities.add(RoomEntity("binary_sensor.co")) }
        fun chip(smoke: Boolean, co: Boolean) = Present.chips(room, mapOf(
            "binary_sensor.s" to det("binary_sensor.s", "smoke", smoke), "binary_sensor.co" to det("binary_sensor.co", "carbon_monoxide", co))).single { it.kind == "smoke" }
        assertEquals(0, chip(false, false).level)
        assertEquals(2, chip(true, false).level)
        assertEquals(2, chip(false, true).level)
        assertEquals("smoke", Present.chipKind("binary_sensor.s", det("binary_sensor.s", "smoke", false)))
        assertTrue(Present.isHeaderSensor("binary_sensor.s", det("binary_sensor.s", "smoke", false)))
    }

    @Test fun artistPhoto() {
        assertEquals(listOf("Earth, Wind & Fire", "Earth"), Posters.artistNames("Earth, Wind & Fire"))
        assertEquals(listOf("Loud feat. Lary Kidd", "Loud"), Posters.artistNames("Loud feat. Lary Kidd"))
        assertEquals(listOf("Daniel Bélanger"), Posters.artistNames(" Daniel Bélanger "))
        val json = """{"data":[{"name":"Daniel","picture_xl":"https://x/artist/aaa/1000.jpg"},{"name":"Daniel Belanger","picture_xl":"https://x/artist/bbb/1000.jpg"}]}"""
        assertEquals("https://x/artist/bbb/1000.jpg", Posters.fromDeezerArtist(json, "Daniel Bélanger"))
        assertEquals("", Posters.fromDeezerArtist(json, "Quelqu'un d'autre"))
        // Silhouette grise de Deezer : pas une photo.
        assertEquals("", Posters.fromDeezerArtist("""{"data":[{"name":"Inconnu","picture_xl":"https://x/artist//1000.jpg"}]}""", "Inconnu"))
        assertEquals("", Posters.fromDeezerArtist(null, "x"))
    }

    @Test fun thermostatChip() {
        val heat = EntityState("climate.a", "heat", JSONObject().put("current_temperature", 19.5).put("temperature", 21).put("hvac_action", "heating"))
        val off = EntityState("climate.b", "off", JSONObject().put("current_temperature", 18.5))
        assertEquals("19,5\u00A0°C → 21\u00A0°C".replace(',', java.text.DecimalFormatSymbols(I18n.locale).decimalSeparator), Present.climateChip(heat))
        assertEquals("70\u00A0°F", Present.degrees(70.0, "°F")); assertEquals("20°", Present.degrees(20.0, ""))
        assertTrue(Present.climateChip(off).startsWith("18"))
        val room = Room("r", "Chambre").apply { entities.add(RoomEntity("climate.a")); entities.add(RoomEntity("climate.b")) }
        val chips = Present.chips(room, mapOf("climate.a" to heat, "climate.b" to off))
        assertEquals(listOf("climate.a", "climate.b"), chips.filter { it.kind == "climate" }.map { it.entity })
        assertTrue(chips.none { it.kind == "temp" })
        assertTrue(Present.isHeaderSensor("climate.a", heat))
    }

    @Test fun openCoversChip() {
        fun cover(id: String, dc: String, open: Boolean) = EntityState(id, if (open) "open" else "closed", JSONObject().put("device_class", dc))
        val room = Room("r", "Cuisine").apply { listOf("cover.s1", "cover.s2", "cover.r1").forEach { entities.add(RoomEntity(it)) } }
        fun chip(s1: Boolean, s2: Boolean, r1: Boolean) = Present.chips(room, mapOf("cover.s1" to cover("cover.s1", "shade", s1),
            "cover.s2" to cover("cover.s2", "shade", s2), "cover.r1" to cover("cover.r1", "curtain", r1))).firstOrNull { it.kind == "cover" }
        assertNull(chip(false, false, false))                       // rien d'ouvert : pas de pastille
        assertTrue(chip(true, true, false)!!.text.startsWith("2 "))
        assertEquals(R.drawable.ic_curtain, chip(false, false, true)!!.icon)
        assertTrue(chip(true, false, true)!!.text.startsWith("2 "))
    }

    @Test fun temperatureThenHumidityThenThermostat() {
        val room = Room("r", "Salon").apply { listOf("climate.a", "sensor.h", "sensor.t").forEach { entities.add(RoomEntity(it)) } }
        val chips = Present.chips(room, mapOf(
            "climate.a" to EntityState("climate.a", "heat", JSONObject().put("current_temperature", 20).put("temperature", 21)),
            "sensor.h" to EntityState("sensor.h", "45", JSONObject().put("device_class", "humidity")),
            "sensor.t" to EntityState("sensor.t", "20", JSONObject().put("device_class", "temperature").put("friendly_name", "Salon"))))
        assertEquals(listOf("temp", "hum", "climate"), chips.map { it.kind })
    }

    @Test fun sectionKeysAreKnown() {
        assertEquals(15, SECTIONS.size); assertEquals(SECTIONS.size, SECTIONS.distinct().size)
        assertTrue(listOf("lists", "music", "weather", "agenda").all { it in SECTIONS })
    }

    @Test fun railPages() {
        assertEquals(1, Rail.pages(0)); assertEquals(1, Rail.pages(10)); assertEquals(2, Rail.pages(11)); assertEquals(2, Rail.pages(15))
        assertEquals((0 until 10).toList(), Rail.range(0, 15).toList())
        assertEquals((10 until 15).toList(), Rail.range(1, 15).toList())
        assertEquals(0, Rail.pageOf(9)); assertEquals(1, Rail.pageOf(10))
    }

    @Test fun scoreDurations() {
        fun game(state: String, start: Long) = Scores.Game(state, Scores.Side("A", "", "", "1"), Scores.Side("B", "", "", "2"), "", start, "", "")
        val start = 100 * 3_600_000L
        // Réglage « 3 h avant » : un match dans 5 heures ne s'affiche pas encore ; avec 12 h (défaut), oui.
        assertFalse(Scores.visible(game("PRE", start), start - 5 * 3_600_000L, before = 3, after = 8))
        assertTrue(Scores.visible(game("PRE", start), start - 5 * 3_600_000L))
        // Réglage « 4 h après le début » : le pointage final disparaît plus tôt.
        assertFalse(Scores.visible(game("POST", start), start + 6 * 3_600_000L, before = 12, after = 4))
        assertTrue(Scores.visible(game("POST", start), start + 6 * 3_600_000L, before = 12, after = 24))
    }

    @Test fun safetyAlarmSteps() {
        assertEquals("smoke", SafetyAlarm.kind("binary_sensor.a", "smoke"))
        assertEquals("co", SafetyAlarm.kind("binary_sensor.a", "carbon_monoxide"))
        assertEquals("leak", SafetyAlarm.kind("binary_sensor.a", "moisture"))
        assertNull(SafetyAlarm.kind("binary_sensor.a", "door")); assertNull(SafetyAlarm.kind("sensor.a", "smoke"))
        val ringing = HashSet<String>(); val silenced = HashSet<String>()
        assertTrue(SafetyAlarm.step("d", true, ringing, silenced))    // se déclenche : sonne
        assertFalse(SafetyAlarm.step("d", true, ringing, silenced))   // encore en alerte : pas une nouvelle alarme
        silenced.addAll(ringing); ringing.clear()                     // « Arrêter la sonnerie »
        assertFalse(SafetyAlarm.step("d", true, ringing, silenced))   // toujours en alerte : reste silencieux
        assertFalse(SafetyAlarm.step("d", false, ringing, silenced))  // retour au calme
        assertTrue(silenced.isEmpty())
        assertTrue(SafetyAlarm.step("d", true, ringing, silenced))    // nouvelle alerte : resonne
    }

    @Test fun bulkActions() {
        fun e(id: String, st: String) = EntityState(id, st, JSONObject())
        assertTrue(Present.bulkNeeded("light.a", e("light.a", "on"))); assertFalse(Present.bulkNeeded("light.a", e("light.a", "off")))
        assertTrue(Present.bulkNeeded("cover.a", e("cover.a", "open"))); assertFalse(Present.bulkNeeded("cover.a", e("cover.a", "closed")))
        assertTrue(Present.bulkNeeded("lock.a", e("lock.a", "unlocked"))); assertFalse(Present.bulkNeeded("lock.a", e("lock.a", "locked")))
        assertFalse(Present.bulkNeeded("light.a", e("light.a", "unavailable"))); assertFalse(Present.bulkNeeded("light.a", null))
        assertNull(Present.bulkLabel("door")); assertNull(Present.bulkLabel("window"))
    }

    @Test fun healthChips() {
        val list = Health.parse("""[["Bouton Salon",10],["Tablette Cuisine",12],["Serrure",-1],["PIR",14]]""", "tablette cuisine")
        assertEquals(listOf("Serrure", "Bouton Salon", "PIR"), list.map { it.name })   // la tablette elle-même ne compte pas
        assertEquals("battery", Health.batteryChip(list)!!.kind); assertNull(Health.batteryChip(emptyList()))
        assertTrue(Health.parse(null).isEmpty()); assertTrue(Health.parse("pas du json").isEmpty())
        assertTrue("<= 20" in Health.template(20))
        val room = Room("r", "Maison").apply { for (id in listOf("light.a", "light.b", "media_player.tv", "camera.c", "light.hidden")) entities.add(RoomEntity(id)) }
        val states = room.entities.associate { it.id to EntityState(it.id, if (it.id == "light.b") "on" else "unavailable", JSONObject()) }
        // Les lecteurs et les caméras ne comptent pas, ni un appareil décoché de la vue d'ensemble.
        assertEquals(listOf("light.a"), Health.offline(room, states, setOf("light.hidden")).map { it.id })
        assertNull(Health.offlineChip(0)); assertEquals(1, Health.offlineChip(2)!!.level)
    }

    @Test fun familyNotes() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 10, 15, 9, 30, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val midnight = FamilyNotes.until("today", now)
        // « Jusqu'à minuit » : la nuit qui suit ; « demain soir » : un jour de plus ; « sans fin » : 0.
        assertEquals((24 - 9) * 3_600_000L - 30 * 60_000L, midnight - now)
        assertEquals(86_400_000L, FamilyNotes.until("tomorrow", now) - midnight)
        assertEquals(0L, FamilyNotes.until("forever", now))
        val list = listOf(FamilyNotes.Note(1, "Ancienne", "A", now - 3_600_000L, midnight), FamilyNotes.Note(2, "Sans fin", "", now - 60_000L, 0L),
            FamilyNotes.Note(3, "Échue", "", now - 2 * 86_400_000L, now - 1))
        // Les notes échues disparaissent ; la plus récente d'abord.
        assertEquals(listOf(2L, 1L), FamilyNotes.valid(list, now).map { it.id })
        assertEquals(listOf(2L), FamilyNotes.valid(list, midnight).map { it.id })
        // Aller-retour par le stockage, et texte nettoyé.
        assertEquals(listOf("Ancienne", "Sans fin", "Échue"), FamilyNotes.parse(FamilyNotes.toJson(list).toString()).map { it.text })
        assertTrue(FamilyNotes.parse("pas du json").isEmpty())
        assertEquals("Souper à 18 h", FamilyNotes.clean("  Souper\n à   18 h "))
        assertEquals(FamilyNotes.MAX_LENGTH, FamilyNotes.clean("x".repeat(500)).length)
    }

    @Test fun unlockNeedsConfirmation() {
        fun e(st: String) = EntityState("lock.a", st, JSONObject())
        assertTrue(Present.wouldUnlock("lock.a", e("locked")))
        // Verrouiller, ou toucher autre chose qu'une serrure, ne demande rien.
        assertFalse(Present.wouldUnlock("lock.a", e("unlocked"))); assertFalse(Present.wouldUnlock("lock.a", e("unavailable")))
        assertFalse(Present.wouldUnlock("light.a", EntityState("light.a", "locked", JSONObject()))); assertFalse(Present.wouldUnlock("lock.a", null))
    }

    @Test fun historyAxis() {
        val pts = listOf(Energy.Point(0, 20.4), Energy.Point(1, 21.9), Energy.Point(2, 19.8))
        assertEquals(19.8 to 21.9, Energy.range(pts))
        val (lo, hi) = Energy.axis(19.8, 21.9)
        assertTrue(lo <= 19.8 && hi >= 21.9 && lo > 15 && hi < 26)       // l'axe serre la courbe, il ne part pas de zéro
        val (a, b) = Energy.axis(21.0, 21.0)
        assertTrue(b - a >= 2)                                            // une valeur constante garde un axe lisible
    }

    @Test fun vacuumRooms() {
        val attrs = JSONObject().put("selected_map", "Carte 2").put("rooms", JSONObject()
            .put("Carte 1", org.json.JSONArray().put(JSONObject().put("id", 9).put("name", "Garage")))
            .put("Carte 2", org.json.JSONArray().put(JSONObject().put("id", 2).put("name", "Bureau")).put(JSONObject().put("id", 7).put("name", "Séjour")).put(JSONObject().put("name", "Sans numéro"))))
        assertEquals(listOf(2 to "Bureau", 7 to "Séjour"), Present.vacuumRooms(EntityState("vacuum.a", "docked", attrs)))
        // Sans carte sélectionnée : la première ; un autre aspirateur : rien.
        attrs.remove("selected_map")
        assertEquals(listOf(9 to "Garage"), Present.vacuumRooms(EntityState("vacuum.a", "docked", attrs)))
        assertTrue(Present.vacuumRooms(EntityState("vacuum.b", "docked", JSONObject())).isEmpty()); assertTrue(Present.vacuumRooms(null).isEmpty())
    }

    @Test fun birthdaysAndNight() {
        // Un prénom devient « Bonne fête, … » ; un titre qui parle déjà d'anniversaire reste tel quel.
        assertEquals("Bonne fête, Florianne", HomeCare.birthdayText(" Florianne "))
        assertEquals("Joyeux anniversaire!", HomeCare.birthdayText("Joyeux anniversaire!"))
        assertEquals("Fête de Laurent", HomeCare.birthdayText("Fête de Laurent"))
        val cals = listOf("calendar.famille", "calendar.anniversaires")
        assertEquals("calendar.anniversaires", HomeCare.birthdayCalendar("", cals))
        assertEquals("", HomeCare.birthdayCalendar("none", cals)); assertEquals("", HomeCare.birthdayCalendar("", listOf("calendar.famille")))
        assertEquals("calendar.famille", HomeCare.birthdayCalendar("calendar.famille", cals))
        // Raccourci du soir : reconnu à son nom en automatique, ou choisi, ou jamais.
        assertTrue(HomeCare.isNightShortcut("", "script.bonne_nuit", "Bonne nuit")); assertFalse(HomeCare.isNightShortcut("", "scene.match_de_hockey", "Match de hockey"))
        assertTrue(HomeCare.isNightShortcut("", "script.goodnight", "Good night")); assertFalse(HomeCare.isNightShortcut("", "scene.movie_night", "Soirée cinéma"))
        assertFalse(HomeCare.isNightShortcut("none", "script.bonne_nuit", "Bonne nuit")); assertTrue(HomeCare.isNightShortcut("scene.x", "scene.x", "Cinéma"))
        // Ce qui reste à régler.
        fun e(id: String, st: String, dc: String = "") = EntityState(id, st, JSONObject().apply { if (dc.isNotEmpty()) put("device_class", dc) })
        val room = Room("r", "Maison").apply { for (id in listOf("light.a", "light.b", "lock.a", "lock.b", "binary_sensor.w", "binary_sensor.d", "binary_sensor.m")) entities.add(RoomEntity(id)) }
        val n = HomeCare.night(room, mapOf("light.a" to e("light.a", "on"), "light.b" to e("light.b", "off"), "lock.a" to e("lock.a", "unlocked"), "lock.b" to e("lock.b", "locked"),
            "binary_sensor.w" to e("binary_sensor.w", "on", "window"), "binary_sensor.d" to e("binary_sensor.d", "off", "door"), "binary_sensor.m" to e("binary_sensor.m", "on", "motion")))
        assertEquals(listOf("light.a"), n.lights.map { it.id }); assertEquals(listOf("lock.a"), n.locks.map { it.id }); assertEquals(listOf("binary_sensor.w"), n.openings.map { it.id })
        assertTrue(n.fixable); assertFalse(n.clear)
        assertTrue(HomeCare.night(room, mapOf("light.b" to e("light.b", "off"), "lock.b" to e("lock.b", "locked"))).clear)
    }

    @Test fun trackedAppliances() {
        // Au repos ou au travail, quelle que soit la casse ; les états d'un four LG et d'une imprimante Bambu.
        for (s in listOf("idle", "Inactive", "initial", "finish", "off", "unavailable", "cleaning_is_done")) assertFalse(s, Trackers.active(s))
        for (s in listOf("running", "preheating", "cooking_in_progress", "Lavage", "pause")) assertTrue(s, Trackers.active(s))
        assertFalse(Trackers.active(null))
        assertEquals("Cooking in progress", Trackers.stateText("cooking_in_progress").let { if (it == "Cuisson") "Cooking in progress" else it })
        assertEquals("Lavage", Trackers.stateText("Lavage"))
        // Temps restant : heures, minutes, secondes, ou heure de fin.
        fun e(st: String, unit: String = "") = EntityState("sensor.x", st, JSONObject().apply { if (unit.isNotEmpty()) put("unit_of_measurement", unit) })
        assertEquals(75, Trackers.remainingMinutes(e("1.25", "h"), 0)); assertEquals(25, Trackers.remainingMinutes(e("25", "min"), 0)); assertEquals(2, Trackers.remainingMinutes(e("120", "s"), 0))
        assertEquals(90, Trackers.remainingMinutes(e("2026-11-15T17:00:00+00:00"), 1794756600000L))   // 15 h 30 UTC
        assertNull(Trackers.remainingMinutes(e("0", "min"), 0)); assertNull(Trackers.remainingMinutes(e("unknown"), 0)); assertNull(Trackers.remainingMinutes(null, 0))
        assertEquals("25 min", Trackers.remainingText(25)); assertEquals("1 h 15", Trackers.remainingText(75)); assertEquals("2 h", Trackers.remainingText(120))
        // Configuration : sans entité d'état, l'appareil est ignoré ; sans pièce et au travail, il fait une pastille.
        val list = Trackers.parse(org.json.JSONArray().put(JSONObject().put("name", "Laveuse").put("icon", "🧺").put("entity", "sensor.w").put("remaining", "sensor.r"))
            .put(JSONObject().put("name", "Imprimante").put("entity", "sensor.p").put("progress", "sensor.pp").put("room", "bureau")).put(JSONObject().put("name", "Vide")))
        assertEquals(listOf("Laveuse", "Imprimante"), list.map { it.name })
        val states = mapOf("sensor.w" to e("Lavage"), "sensor.r" to e("25", "min"), "sensor.p" to e("running"), "sensor.pp" to e("50", "%"))
        val chips = Trackers.chips(list, states, 0)
        assertEquals(1, chips.size); assertTrue(chips[0].text.startsWith("🧺 Laveuse : Lavage")); assertTrue("25 min" in chips[0].text)
        assertTrue(Trackers.chips(list, mapOf("sensor.w" to e("Inactive")), 0).isEmpty())
        assertTrue("50 %" in Trackers.statusLine(list[1], states, 0))
    }

    @Test fun vacuumMaintenance() {
        val list = Health.parseWear("""[["Léon","Filtre",8],["Léon","Avertissement de niveau d'eau bas",-1],["Robbie","Brosse latérale",3]]""")
        assertEquals(listOf(-1, 3, 8), list.map { it.pct })
        assertTrue(Health.wearChip(list)!!.text.contains("2")); assertTrue(Health.wearChip(list.filter { it.device == "Léon" })!!.text.endsWith("Léon"))
        assertNull(Health.wearChip(emptyList())); assertTrue(Health.parseWear(null).isEmpty())
        assertTrue("<= 10" in Health.wearTemplate(10))
    }

    @Test fun cockpitSkinIsListedAndCutsItsCorners() {
        assertTrue("cockpit" in Skin.keys)
        Skin.use("cockpit"); assertTrue(Skin.tileCut > 0f); assertTrue(Skin.gauges)
        Skin.use("homekit"); assertEquals(0f, Skin.tileCut); assertFalse(Skin.gauges)
    }

    @Test fun dialsPlaceValuesOnTheirScales() {
        assertEquals(0f, Dial.temperature(null, "°C")); assertEquals(0.5f, Dial.temperature(5.0, "°C"), 0.001f); assertEquals(0.5f, Dial.temperature(41.0, "°F"), 0.001f)
        assertEquals(1f, Dial.temperature(60.0, "°C")); assertEquals(0f, Dial.temperature(-50.0, "°C"))
        assertEquals(2.4, Dial.kilowatts(2400.0, "W")!!, 0.001); assertEquals(2.4, Dial.kilowatts(2.4, "kW")!!, 0.001); assertNull(Dial.kilowatts(null, "W"))
        assertEquals(0.24f, Dial.level(2.4, 0.0, 10.0), 0.001f); assertEquals(1f, Dial.level(250.0, 0.0, 100.0)); assertEquals(0f, Dial.level(null, 0.0, 1.0))
        assertEquals(150f, Dial.angle(0f)); assertEquals(270f, Dial.angle(0.5f)); assertEquals(390f, Dial.angle(2f))
    }

    @Test fun cockpitTilesDrawASwitchOnlyWhenTheSkinAsks() {
        Skin.use("cockpit"); assertTrue(Skin.tile(true, 0).toggle); assertTrue(Skin.tile(false, 0).toggle); assertTrue(Skin.tile(true, 0).dot != 0)
        Skin.use("homekit"); assertFalse(Skin.tile(true, 0).toggle)
    }

    @Test fun launchSequenceReadsEveryActionOfAScript() {
        assertTrue(Launch.applies("scene.x")); assertTrue(Launch.applies("script.x")); assertFalse(Launch.applies("light.x"))
        val scene = EntityState("scene.x", "on", org.json.JSONObject().put("entity_id", org.json.JSONArray(listOf("light.a", "cover.b"))))
        assertEquals(listOf("light.a", "cover.b"), Launch.targets(scene)); assertTrue(Launch.targets(null).isEmpty())
        val script = org.json.JSONObject("""{"alias":"Bonne nuit","sequence":[
            {"if":[{"condition":"state","entity_id":"media_player.tv","state":"on"}],"then":[{"action":"media_player.turn_off","target":{"entity_id":["media_player.tv"]}}]},
            {"action":"light.turn_off","target":{"entity_id":["light.a","light.b"],"area_id":"sous_sol","label_id":["nuit"]}},
            {"service":"lock.lock","data":{"entity_id":"lock.c, lock.d"}},
            {"service":"homeassistant.turn_off","entity_id":"group.tout","target":{"floor_id":"etage","device_id":"abc123"}},
            {"choose":[{"sequence":[{"action":"cover.close_cover","target":{"entity_id":"cover.e"}}]}],"default":[{"action":"scene.turn_on","target":{"entity_id":"scene.soir"}}]},
            {"repeat":{"sequence":[{"action":"switch.turn_on","target":{"entity_id":"switch.f"}},{"delay":{"seconds":20}}],"until":[{"condition":"state","entity_id":"switch.zzz","state":"on"}]}},
            {"action":"script.fermer_garage"},{"action":"script.turn_on","target":{"entity_id":"script.autre"}},
            {"action":"light.turn_off","target":{"entity_id":"{{ states.light | map(attribute='entity_id') | list }}"}},
            {"action":"{{ 'light.turn_on' }}","target":{"entity_id":"light.modele"}},
            {"action":"notify.mobile","data":{"message":"x","data":{"tag":"y"}}}]}""")
        val calls = Launch.calls(script)
        // L'ordre des branches d'un même bloc n'est pas garanti : on compare sans ordre.
        assertEquals(listOf("media_player.turn_off", "light.turn_off", "lock.lock", "homeassistant.turn_off", "cover.close_cover", "scene.turn_on", "switch.turn_on",
            "script.fermer_garage", "script.turn_on", "light.turn_off", "notify.mobile").sorted(), calls.map { it.domain + "." + it.service }.sorted())
        fun call(name: String, pick: (Launch.Call) -> Boolean = { true }) = calls.first { it.domain + "." + it.service == name && pick(it) }
        val lights = call("light.turn_off") { !it.empty }
        assertEquals(listOf("light.a", "light.b"), lights.entities); assertEquals(listOf("sous_sol"), lights.areas); assertEquals(listOf("nuit"), lights.labels)
        assertEquals(listOf("lock.c", "lock.d"), call("lock.lock").entities)
        val all = call("homeassistant.turn_off")
        assertEquals(listOf("group.tout"), all.entities); assertEquals(listOf("etage"), all.floors); assertEquals(listOf("abc123"), all.devices)
        assertEquals(listOf("scene.soir"), call("scene.turn_on").scenes)
        // Les conditions ne sont pas des actions, et une cible calculée par un modèle ne donne rien.
        assertTrue(calls.none { "switch.zzz" in it.entities || "light.modele" in it.entities || "media_player.zzz" in it.entities })
        assertTrue(call("light.turn_off") { it.empty }.empty); assertTrue(call("notify.mobile").empty)
        assertEquals(setOf("script.fermer_garage", "script.autre"), calls.flatMap { it.scripts }.toSet())
        assertTrue(Launch.calls(null).isEmpty()); assertTrue(Launch.calls(org.json.JSONObject("""{"sequence":[]}""")).isEmpty())
    }

    @Test fun launchSequenceKnowsTheWantedStateOfCommonServices() {
        assertEquals("off", Launch.expected("light", "turn_off")); assertEquals("on", Launch.expected("switch", "turn_on")); assertEquals("locked", Launch.expected("lock", "lock"))
        assertEquals("closed", Launch.expected("cover", "close_cover")); assertEquals("", Launch.expected("light", "toggle")); assertEquals("", Launch.expected("scene", "turn_on"))
        assertEquals("", Launch.expected("climate", "set_temperature"))
        assertTrue(Launch.reached("off", "off")); assertTrue(Launch.reached("off", "standby")); assertFalse(Launch.reached("off", "on")); assertFalse(Launch.reached("off", "unavailable"))
        assertTrue(Launch.reached("on", "on")); assertTrue(Launch.reached("on", "playing")); assertFalse(Launch.reached("on", "off")); assertFalse(Launch.reached("on", "unavailable"))
        assertTrue(Launch.reached("locked", "locked")); assertFalse(Launch.reached("locked", "locking")); assertFalse(Launch.reached("", "on"))
    }

    @Test fun launchSequenceBuildsOneTemplateAndMergesItsAnswer() {
        assertEquals("", Launch.template(emptyList())); assertEquals("", Launch.template(listOf(Launch.Call("notify", "mobile", emptyList()), Launch.Call("script", "turn_on", listOf("script.x")))))
        val t = Launch.template(listOf(Launch.Call("light", "turn_off", listOf("light.a"), areas = listOf("salon")), Launch.Call("scene", "turn_on", listOf("scene.soir")),
            Launch.Call("homeassistant", "turn_off", listOf("group.tout"))))
        assertTrue(t.contains("""'e':["light.a"]""")); assertTrue(t.contains("""'a':["salon"]""")); assertTrue(t.contains("'x':'off'")); assertTrue(t.contains("""'m':"^light\\.""""))
        assertTrue(t.contains("""'s':["scene.soir"]""")); assertTrue(t.contains("""'e':["group.tout"]""")); assertTrue(t.contains("area_entities")); assertTrue(t.endsWith("{{ ns.r | tojson }}"))
        val rows = Launch.parseRows("""[["light.a","Plafonnier","on","255/None/None","off"],["lock.b","Serrure","locked","None/None/None","locked"],
            ["light.a","Plafonnier","on","255/None/None","on"],["cover.c","","open","None/60/None",""]]""")
        assertEquals(listOf("light.a", "lock.b", "cover.c"), rows.map { it.id })
        assertEquals("", rows[0].expect); assertEquals("locked", rows[1].expect); assertEquals("cover.c", rows[2].name); assertEquals("open#None/60/None", rows[2].print)
        assertTrue(Launch.parseRows(null).isEmpty()); assertTrue(Launch.parseRows("pas du json").isEmpty())
        // Même empreinte que le modèle pour un appareil que la tablette suit déjà.
        assertEquals("on#255/None/None", Launch.print(EntityState("light.a", "on", org.json.JSONObject().put("brightness", 255).put("current_position", org.json.JSONObject.NULL))))
        assertEquals("open#None/60/None", Launch.print(EntityState("cover.c", "open", org.json.JSONObject().put("current_position", 60))))
        assertEquals(listOf("a", "b"), Launch.rows(listOf("a", "b")))
        val many = Launch.rows((1..15).map { "n$it" })
        assertEquals(Launch.MAX_ROWS, many.size); assertTrue(many.last().startsWith("+ 4"))
        assertTrue(Launch.litAt(1) > Launch.litAt(0)); assertTrue(Launch.total(6) > Launch.litAt(5)); assertTrue(Launch.total(0) < 2000)
    }

    @Test fun radarPlacesRoomsInsideTheCircleAndFadesBehindTheSweep() {
        for (n in 1..15) for (i in 0 until n) { val (a, d) = Radar.place(i, n); assertTrue(a in 0f..360f); assertTrue(d in 0.4f..0.9f) }
        assertTrue(Radar.place(0, 4).first != Radar.place(1, 4).first)
        assertEquals(1f, Radar.glow(90f, 90f), 0.001f); assertTrue(Radar.glow(90f, 100f) > Radar.glow(90f, 300f)); assertTrue(Radar.glow(90f, 80f) >= 0.3f)
    }

    @Test fun radarCountsWhatIsOnOpenAndUnlocked() {
        val room = Room("r", "Salon").apply { entities.addAll(listOf(RoomEntity("light.a"), RoomEntity("lock.b"), RoomEntity("binary_sensor.c"))) }
        val calm = Room("q", "Bureau").apply { entities.add(RoomEntity("light.z")) }
        fun st(id: String, s: String, dc: String = "") = id to EntityState(id, s, org.json.JSONObject().put("device_class", dc))
        val states = mapOf(st("light.a", "on"), st("lock.b", "unlocked"), st("binary_sensor.c", "on", "door"), st("light.z", "off"))
        val s = Radar.scan(listOf(room, calm), states)
        assertEquals(1, s.lights); assertEquals(1, s.open); assertEquals(1, s.unlocked); assertEquals(1, s.locks)
        assertEquals(listOf("Salon"), s.blips.map { it.name }); assertTrue(s.blips[0].alert)
        assertEquals(3, Radar.lines(s).size)
        val quiet = Radar.scan(listOf(calm), states)
        assertTrue(quiet.blips.isEmpty()); assertEquals(1, Radar.lines(quiet).size)
    }

    @Test fun dashboardKeepsTheHouseSystemsInPlace() {
        val lock = Present.Chip(1, "Verrouillé", kind = "lock"); val power = Present.Chip(2, "2,4 kW", kind = "energy"); val window = Present.Chip(3, "Fenêtre ouverte", 1, "window")
        val out = Present.standing(listOf(power, window, lock), setOf("light", "lock", "door", "window"))
        assertEquals(listOf("light", "lock", "door", "window", "energy"), out.map { it.kind })
        assertEquals(0, out[0].level); assertEquals(1, out[3].level); assertEquals("Verrouillé", out[1].text)
        // Une maison sans porte suivie n'a pas de voyant « Portes » ; sans détecteur, pas de voyant de fumée inventé.
        assertEquals(listOf("lock", "energy"), Present.standing(listOf(power, lock), setOf("lock", "smoke")).map { it.kind })
        // Tout est verrouillé : la pastille « Verrouillé » est filtrée sur l'accueil, mais le voyant « Serrures » reste, éteint.
        assertEquals(listOf("lock", "energy"), Present.standing(listOf(power), setOf("lock")).map { it.kind }); assertEquals(0, Present.standing(listOf(power), setOf("lock"))[0].level)
        assertTrue(Present.standing(emptyList(), emptySet()).isEmpty())
    }

    @Test fun segmentDigitsLightTheRightBars() {
        assertEquals(0b0000110, Segments.mask('1')); assertEquals(0b1111111, Segments.mask('8')); assertEquals(0b0111111, Segments.mask('0')); assertEquals(0, Segments.mask(':'))
        assertEquals(10, ('0'..'9').map { Segments.mask(it) }.distinct().size)
        assertTrue(Segments.width(':') < Segments.width('8'))
    }

    @Test fun radarAddsScoresAndWhatIsPlaying() {
        val g = Scores.Game("IN", Scores.Side("MTL", "Canadiens", "", "2"), Scores.Side("TOR", "Maple Leafs", "", "1"), "12:04", 0L, "NHL", "")
        val lines = Radar.extras(listOf(g, g, g), "Blue in Green · Miles Davis")
        assertEquals(3, lines.size); assertTrue(lines[0].startsWith("MTL 2 – 1 TOR")); assertTrue(lines[2].endsWith("Blue in Green · Miles Davis"))
        assertTrue(Radar.extras(emptyList(), " ").isEmpty())
    }

    @Test fun masterCautionLightsForUnseenRedAlertsOnly() {
        assertEquals(false to emptySet<String>(), Present.caution(emptySet(), emptySet()))
        assertEquals(true to emptySet<String>(), Present.caution(setOf("Serrure bloquée"), emptySet()))
        // Vue : le voyant s'éteint tant que rien de nouveau n'arrive.
        assertEquals(false to setOf("Serrure bloquée"), Present.caution(setOf("Serrure bloquée"), setOf("Serrure bloquée")))
        assertEquals(true to setOf("Serrure bloquée"), Present.caution(setOf("Serrure bloquée", "Fumée détectée"), setOf("Serrure bloquée")))
        // L'alerte disparaît puis revient : elle rallume le voyant.
        val (_, still) = Present.caution(emptySet(), setOf("Serrure bloquée")); assertTrue(still.isEmpty())
        assertTrue(Present.caution(setOf("Serrure bloquée"), still).first)
    }

    @Test fun powerDialKnowsWhenAPeakIsRunning() {
        val peaks = listOf(1000L to 2000L, 5000L to 6000L)
        assertFalse(Dial.inPeak(peaks, 999)); assertTrue(Dial.inPeak(peaks, 1000)); assertTrue(Dial.inPeak(peaks, 1999)); assertFalse(Dial.inPeak(peaks, 2000))
        assertTrue(Dial.inPeak(peaks, 5500)); assertFalse(Dial.inPeak(emptyList(), 1500))
    }
}
