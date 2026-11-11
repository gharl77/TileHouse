package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedsTest {
    private fun parse(xml: String) = Feeds.parse(xml.byteInputStream(Charsets.UTF_8))

    @Test fun rss() {
        val items = parse("""<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Journal</title>
            <item><title>Premier titre</title><description><![CDATA[<p>Un <b>résumé</b> court.</p>]]></description>
              <pubDate>Tue, 10 Nov 2026 12:00:00 +0000</pubDate>
              <media:content url="https://exemple.com/a.jpg" medium="image"/></item>
            <item><title>Deuxième</title><enclosure url="https://exemple.com/b.png" type="image/png"/></item>
            </channel></rss>""")
        assertEquals(2, items.size)
        assertEquals("Premier titre", items[0].title)
        assertEquals("Un résumé court.", items[0].summary)
        assertEquals("https://exemple.com/a.jpg", items[0].image)
        assertEquals(1794312000000L, items[0].date)
        assertEquals("https://exemple.com/b.png", items[1].image)
        assertEquals("", items[1].summary)
    }

    @Test fun atom() {
        val items = parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Blog</title>
            <entry><title>Une entrée</title><summary>Le résumé</summary><updated>2026-11-10T12:00:00Z</updated>
              <link rel="enclosure" type="image/jpeg" href="https://exemple.com/c.jpg"/></entry></feed>""")
        assertEquals(1, items.size)
        assertEquals("Une entrée", items[0].title)
        assertEquals("Le résumé", items[0].summary)
        assertEquals("https://exemple.com/c.jpg", items[0].image)
        assertEquals(1794312000000L, items[0].date)
    }

    /** Flux réels mal formés : « & » seuls dans les liens d'images (vu sur un grand site de nouvelles). */
    @Test fun toleratesBareAmpersands() {
        val items = parse("""<rss version="2.0" xmlns:media="https://search.yahoo.com/mrss/"><channel>
            <item><title>Titre</title><media:content url="https://exemple.com/i.jpg?impolicy=crop&x=0&y=10" medium="image"/></item>
            </channel></rss>""")
        assertEquals("https://exemple.com/i.jpg?impolicy=crop&x=0&y=10", items.single().image)
    }

    @Test fun imageFromSummaryWhenNoEnclosure() {
        val items = parse("""<rss version="2.0"><channel><item><title>T</title>
            <description><![CDATA[<img src="https://exemple.com/d.jpg"> Texte après l'image]]></description></item></channel></rss>""")
        assertEquals("https://exemple.com/d.jpg", items.single().image)
        assertEquals("Texte après l'image", items.single().summary)
    }

    @Test fun itemsWithoutTitleAreSkippedAndSummaryEqualToTitleIsDropped() {
        val items = parse("""<rss version="2.0"><channel><item><description>Sans titre</description></item>
            <item><title>Même texte</title><description>Même texte</description></item></channel></rss>""")
        assertEquals(1, items.size)
        assertEquals("", items[0].summary)
    }

    @Test fun aWebPageIsNotAFeed() {
        assertThrows(Exception::class.java) { parse("<html><body><p>Bonjour</p></body></html>") }
    }

    @Test fun plainTextDecodesEntitiesAndRemovesTags() {
        assertEquals("L'œuf « dur » & tendre", Feeds.plain("L&apos;&oelig;uf &laquo;&nbsp;dur&nbsp;&raquo; &amp; <i>tendre</i>"))
        assertEquals("é è €", Feeds.plain("&#233; &#xE8; &euro;"))
        assertEquals("Ligne un Ligne deux", Feeds.plain("<p>Ligne un</p><p>Ligne deux</p>"))
        assertEquals("entité inconnue &zzz; gardée", Feeds.plain("entité inconnue &zzz; gardée"))
        assertEquals("", Feeds.plain("   "))
    }

    @Test fun dates() {
        assertEquals(1794312000000L, Feeds.parseDate("Tue, 10 Nov 2026 07:00:00 -0500"))
        assertEquals(1794312000000L, Feeds.parseDate("2026-11-10T07:00:00-05:00"))
        assertEquals(0L, Feeds.parseDate("10 nov. 2026, 16 h 35"))
        assertTrue(Feeds.parseDate("2026-11-10") > 0)
        assertNull(null)
    }
}
