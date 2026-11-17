# Changelog

TileHouse uses calendar versioning: `YEAR.MONTH.N`.

## 2026.11.14

- **Cockpit skin.** An eighth skin that turns the home screen into an instrument panel: the chips become a panel of **warning lamps** in identical cells across the full width — *Lights*, *Locks*, *Doors*, *Windows* and *Smoke* always keep their place, unlit when all is well; the weather card shows three **needle dials** (outside temperature, the home's power in kW — or the wind when no power sensor is chosen — and humidity); lights, plugs and fans are drawn as **toggle switches** with an indicator lamp; the clock uses **seven-segment digits**; scenes and scripts are large square buttons; section titles are engraved labels; cards and tiles have cut corners, amber for what is on and cyan for the rest. In control windows a light, blind or volume is set with a **lever** in a graduated slot, and a thermostat with a graduated scale and a pointer.
- **Master caution (Cockpit skin).** A red *Alert* lamp blinks in the header as soon as a red warning appears (a jammed lock, smoke…). A tap acknowledges it; it lights again when a new red warning arrives, or when one that had cleared comes back.
- **Red zone on the power dial (Cockpit skin).** During an electricity peak, the dial turns red above a threshold you choose (web page, *Home → Electricity*, 3 to 8 kW, 5 by default), and so do the needle and the figure when the home is above it.
- **Launch sequence.** Optional (web page, *Home → Shortcuts*): after a scene or a script is tapped, a short full screen lists the devices concerned and follows their real state; a line turns to *OK* when the device is in the wanted state (off, locked, closed…), then *Ready*. For a script, TileHouse reads its content in Home Assistant: named devices, but also those of an area, a device, a label or a floor, the members of a group, the devices of a scene it activates and the content of the scripts it calls. A target computed by a template cannot be guessed and is not listed. Devices that have not arrived after ten seconds are marked *pending*. One box per shortcut chooses which ones get the sequence.
- **“Before leaving” checklist.** The good-night window (what is still on, unlocked or open, with *Fix everything*) can also follow a leaving shortcut of your choice (web page, *Home → Shortcuts*).
- **Radar screen saver.** A fourth screen saver: a slow radar sweep where each room with something on or open is a dot (amber for lights, red for an open door or window or an unlocked lock), with the time, the weather, the house's status lines, the score of the teams you follow and what is playing. Dots are spread around the circle in room order; it is not a floor plan.
- **Good-night summary: stricter detection.** The automatic detection now needs the full *Good night* (or *Bonne nuit*, *dodo*, *coucher*) in the shortcut's name; a scene called *Movie night* no longer opens the summary. A shortcut chosen by hand is unaffected.

## 2026.11.13

- **Tracked appliances.** A 3D printer, a washer, an oven: give the entity that holds its state (web page, *Devices & health → Tracked appliances*); progress, time left, job name and an image are optional. Attached to a room, the appliance has a card there with its picture, the job, a progress bar and the time left; without a room, it makes a chip on the home screen only while it is working. Each one can send a notification when it has finished.
- **Vacuum maintenance chip.** On the home screen, when a filter, a brush or the water tank of a vacuum that exposes them (Dreame) nears the end of its life; tap it for the list. Threshold under *Home health*.
- **Weather radar.** The weather window has a *Radar* button that shows the image of a radar camera of Home Assistant (for example Environment Canada's, to enable there). Found by its name, or chosen on the web page (*Home → Weather alerts*).
- **Settings regrouped.** Web page: new section *Devices & health* (tracked appliances, home health chips); *Alerts & sounds* now starts with safety (safety alarms, locks, camera alerts), then notifications and sounds; the birthday calendar moves to the *Calendar* card and the good-night summary to *Shortcuts*; *Security & backups* becomes *Access & backups*. Tablet: the *Alerts* tab follows the same order and the *Security* tab becomes *Access*.
- **Screen cleaning mode.** Long-press the clock (or the date): the touch screen is frozen for 30 seconds, with a countdown, so the tablet can be wiped. Hold a finger still on the panel for 3 seconds to finish early.
- **Birthday banner.** On the day of an event in your birthday calendar, a banner shows at the top of the home screen: an event named with a first name gives "Happy birthday, Léa", a title that already mentions a birthday is shown as is. The calendar is found by its name, or chosen on the web page (*Home → Calendar*).
- **Good-night summary.** Ten seconds after the *Good night* shortcut is tapped on the tablet, a window lists what is still on, unlocked or open, with *Turn off*, *Lock* and *Fix everything*; if nothing is left it says so and closes by itself. The shortcut is recognised by its name, or chosen on the web page (*Home → Shortcuts*).
- **Water leak alarm.** A leak detector placed in a room now raises the same full-screen alarm as smoke, in blue, with a softer two-beep sound, until someone taps *Silence the alarm*. Its own switch under *Alerts & sounds → Safety alarms*.
- **Confirm before unlocking.** Tapping a locked lock (tile or chip details) asks *Unlock?* with the name of the door; nothing is sent without the second tap, and the window closes by itself after 15 seconds. Locking stays immediate. Can be turned off under *Alerts & sounds → Locks*.
- **Temperature and humidity history.** In a room, tap the temperature or humidity chip to see the last 24 hours as a curve, with the current value, the minimum and the maximum.
- **Robot vacuum, room by room.** For a vacuum whose integration lists its rooms (Dreame), the control shows the rooms as chips: pick some, then *Clean N rooms*. Start, pause, stop and dock are unchanged.
- **Family messages.** Short notes pinned at the top of the home screen ("Dinner at grandma's at 6"), in both layouts. Write one on the tablet with the **+** (keyboard, who signs it, and how long it stays: until midnight, until tomorrow night, or until erased) or from the web page (*Home → Family messages*). Tap a note to read it in full or erase it. Up to six notes; they stay on the tablet and can be hidden in *Sections shown*.
- **Smoke and carbon monoxide alarm.** When a smoke, carbon monoxide or gas detector placed in a room goes off, the tablet wakes up, sounds a siren (three high beeps and a pause, different from the timer ring) and shows a full red screen with the detector, its room and the time, until someone taps *Silence the alarm*. It sounds during quiet hours too, comes in front of the settings or a camera, and leaves an entry in the notification centre. If the tablet's alarm volume is at zero, a real alarm raises it. Switch and *Test the alarm* under *Alerts & sounds → Safety alarms* (tablet and web page).
- **Turn all off.** The details of the *lights on*, *blinds open* and *unlocked* chips end with one button: *Turn all off*, *Close all* or *Lock all*. It only ever turns off, closes or locks, and disappears once there is nothing left to do.
- **Home health chips**, shown on the home screen only when there is a problem: *low batteries* (every battery of the house at or below a level you choose, 15 % by default; phones and tablets are not counted) and *devices offline* (devices of your rooms that Home Assistant can no longer reach; players and cameras are not counted). Tap one for the list. Settings under *Devices & health → Home health*.
- **Wake on approach**: choose Home Assistant presence or motion sensors (web page, *Screen & sleep*); when one detects someone, the screen leaves the screen saver. This adds to the front-camera option.
- **Brightness by room**: choose the lights of the room where the tablet is (web page, *Screen & sleep*); when they are all off the screen drops to a brightness you choose, and returns to normal as soon as one turns on.
- Weather window (tap the weather card): active weather alerts at the top, then the next hours, then the **next days** with highs, lows and the chance of rain.
- Quiet hours: new switch *Black screen saver during quiet hours*. Unchecked, quiet hours only mute the sounds and the screen saver keeps the clock or the photos.
- Scores: how long before a game the score block appears, and how long after the start the final score stays, are now settings (web page, *Tools & media → Sports*; 12 h and 8 h as before by default).
- A new version of TileHouse is announced once by a notification on the tablet (it stays under the bell); installing is still done from *Settings → About*.

## 2026.11.12

- New setting *Layout* (on the tablet and on the web page, under *Appearance*): **Classic**, the screen as it was, or **Side navigation**. With side navigation the rooms stay in a rail on the left (one tap to change room, no trip back to the home screen; ten rooms per page, with an arrow at the bottom for the next ones), the tools stay at the top right, and in a room the camera and the players sit side by side, idle players on a single line. The skin and the layout are chosen separately; Classic stays the default.
- Side navigation, home screen: under the weather and the agenda, previews of the tools (what is left on your lists, the running timers, the game in progress — tap one to open it), your **favourite devices** as tiles and the **cameras** you choose. Pick the favourites, the cameras and which previews you want (lists, timers, scores) on the web page (*Home → Home favourites*, *Tool previews*, *Home cameras*); each block can be hidden in *Sections shown*.
- Settings regrouped. Web page: the *Header tools* section becomes *Tools & media* and now also holds *Sports* and *Now playing* (posters, TV channels, log), which were under *Home* and *Rooms*; the three cards of the side-navigation home screen have their own group; in *Appearance*, the background comes last. Tablet: the *Display* tab is split into *Appearance*, *Home* (sections shown, tool previews) and *Screen* (brightness, screen saver).
- A dimmable light that is on shows its level as a soft fill across its tile, in both layouts.
- Dimmable lights: slide left or right on the tile to set the brightness; the level fills the tile and is applied when you release (all the way left turns the light off). A tap still switches it, a long press still opens the slider.
- Blinds and curtains: the control now draws the window with its blind or curtain at the real position. A blind comes down from the top, a curtain closes from both sides; drag in the drawing to set the position. The buttons match the movement (up / down, or open / close sideways), stop is shown only when the cover supports it, and curtains have their own tile icon.
- Thermostats are now a chip at the top of their room ("19.5 °C → 21 °C", the icon turns orange while heating) instead of a tile; tap it to open the card. They are not shown on the home screen.
- Thermostats: a new card with the target in large inside a coloured arc you can drag, a flame that lights up while it heats, an on/off button, and the current temperature between two arrows that raise and lower the target.
- The chip for open blinds and curtains now says what is open: "2 blinds open", "1 curtain open" or "3 blinds & curtains open". As before, it only appears when at least one is open.
- Chips for lights, doors and windows: nothing is shown when none is on or open (no more "Door closed" or "Window closed"); otherwise the chip is yellow.
- Temperature chips show their unit ("19 °C"), and the temperature and humidity chips sit next to each other.
- Room icons: a bathtub for bathrooms and a sofa for the living room.
- New setting *Sections shown* (on the tablet and on the web page, under *Appearance*): hide the weather, the agenda, the shortcuts, the electricity chip, the scores, the timers, the lists, Music or This morning without losing their settings.
- Smoke and carbon monoxide detectors are now a chip at the top ("No smoke", red when one goes off) instead of tiles, in their room and on the home screen; tap it to see each detector. Choose which ones count on the web page (*Home → Whole-house overview → Smoke detectors*).
- Every speaker and TV of a room now has its player card: the choice between card and tile is gone. The list on the web page (*Tools & media → Now playing*) now only decides which players count for the TV icon in the top bar and for the screen saver.
- Music: the card, the poster window and the screen saver show the artist's photo (found by name on Deezer) instead of the album cover; the cover is used when no photo is found. Nothing changes for TV.

## 2026.11.11

- Screen saver redesigned: under the time and the date, a row of roomy panels for the weather, the score and what is playing, instead of stacked lines.
- Player cards are now always shown in their room: sober with a power button when the player is off or idle, with the poster and the controls while it plays. A player that has a card no longer has a tile as well; an unticked player stays a tile. The "after a pause" setting is gone, since the card no longer disappears.
- Screen saver: when several players are playing, the panel shows them in turn, ten seconds each.
- Music icon: it only lights up for music started from Music Assistant, not when a speaker is playing something else (AirPlay, the TV's sound); the Music window marks such a speaker "Other source".

## 2026.11.10

- Now playing card: the poster of what is playing, the show or artist with season and episode, the app, and a progress bar you can drag. The poster is the image Home Assistant gives, the video thumbnail for YouTube, or a search by title (TVmaze, iTunes; TMDB and the YouTube Data API with your own optional keys). The search can be turned off on the web page (*Rooms → Now playing*).
- YouTube on a player that only gives the title (Apple TV): the thumbnail is found from the title and the channel, without a key.
- A game on TV: when the title names two teams of the same league (NHL, NFL, NBA, MLB, MLS), their two logos are shown side by side.
- Live TV (Bell Fibe TV on an Apple TV): the card shows the channel, the poster search also tries the series name alone, and the channel's logo is shown when no poster is found.
- Web page: a button to test the TMDB key.
- Web page: a log of what each player reported and where the poster came from (*Rooms → Now playing → Log*).
- Now playing card: a power button for the players that can be turned off, and an applications button for the players that list them (Apple TV): tap one to launch it.
- Scores: pick your teams' TeamTracker sensors on the web page (*Home → Sports*); a trophy icon in the top bar opens the two logos with the score, and the screen saver shows the score under the weather.
- Screen saver: what is playing (image, title, player, app, channel) under the time and the weather.
- Live TV: the channel number is shown once you have typed it on the web page (*Rooms → Now playing → TV channels*).
- A TV icon in the top bar appears while something is playing; tap it to see the poster in large with the title — all of them side by side when several players are playing.

## 2026.11.9

- Music window for Music Assistant: a ♪ icon at the top opens your favourites, playlists, radio stations and albums, with a search field; choose the speaker (or several together), set the volume, and transfer the music that is playing to another speaker. Shuffle, repeat, a progress bar you can drag, and the list of the titles to come (this one needs a Music Assistant token). Choose which speakers are offered on the web page (*Header tools → Music*); without any, the icon stays hidden.
- Home screen: a chip for the blinds and curtains that are open ("2 blinds open"); tap it to see and close them. Choose which ones count on the web page (*Home → Whole-house overview → Blinds & curtains*). Garage doors and gates are not counted.
- Electricity chip: a meter that is read once a day (Hydro-Québec) shows yesterday's consumption ("65 kWh yesterday") instead of what the last reading added.

## 2026.11.8

- Electricity window: a meter that is read once a day (Hydro-Québec) shows the last 14 days, one bar per day, with the average, instead of a single bar on a 24-hour chart.
- Lists: long-press an item to remove it (or to start a timer with its name).
- Calendar: a bell next to the events that have a reminder.
- Automatic weekly copy of the configuration, kept on the tablet without the secrets; download or restore one from the web page (Connection & security → Backup).
- "1 unlocked" / "2 unlocked" instead of "unlocked(s)".
- Web settings page rebuilt: a menu on the left with nine sections (Rooms, Home, Header tools, Appearance, Screen & sleep, Alerts & sounds, Connection, Security & backups, About) instead of six tabs, one column of settings rows, long lists folded with a summary of the current choice, long explanations behind "Learn more", a search field, and a drop-down menu on phones. Every setting is still there; some moved to where they belong (Now playing under Rooms, lists and favourite timers under Header tools).
- Web settings page: multiple choices no longer use checkboxes — toggle chips for short lists, tokens with a search field (and drag to reorder) for long ones, and switches grouped by room for the whole-house overview.
- Custom chips: nine colours instead of three, an icon picker (about fifty icons the tablet can draw, with their Unicode code and a reference link), states picked from the entity's real options, and a live preview of each chip.
- Web settings page: This morning sources are compact rows you drag and open, with the feed status and grouped suggestions; the room editor has clean rows with rename and remove on hover, type filters as chips, and an "already in <room>" note.

## 2026.11.7

- Background: a theme **or** a wallpaper, one or the other. The settings show a single choice (Theme / Wallpaper) with the matching list underneath; choosing one turns the other off, and coming back to *Theme* brings back the last theme used (or the season's).
- Your own photo as the wallpaper: send it from the web page or pick a file on the tablet. It is reduced to the screen size, kept only on the tablet, straightened if the phone saved it sideways, and covered with a veil that depends on the skin so the text stays readable. Pastel and Neon take their colour from it. Demo mode never shows it.
- Home Assistant: new **Background** select (Theme / Wallpaper); the Wallpaper select gains "My photo". Picking a wallpaper sets Theme to "None", and picking a theme switches Background to Theme. An automation that sets the theme will therefore replace a wallpaper: add a condition on Background if you want to keep it.

- Shopping and to-do lists: Home Assistant to-do lists behind a header icon with the number of items left; tick, add, remove the ticked ones, several lists as tabs. Chosen on the web page.
- Now playing card in a room, only while one of its speakers or its TV is playing: title, player, pause, previous, next and volume. The home screen does not show it, and the web page lists the players of your rooms so you choose which ones show the card.
- Weather: tap the card for the next 24 hours, hour by hour. Calendar: tap an event for its end time, place and description.
- Favourite timers: named timers started with one tap, set on the web page. Home Assistant gets "Running timers", "Next timer ends" and a "Timer finished" event.
- Notification history: the last 20 read notifications can be read again from the bell, which stays dimmed in the header while there are some.
- Automatic theme: follows the date (the holidays you tick, otherwise the season), without Home Assistant. New "Theme shown" sensor.
- Wallpaper: "Immich photo of the day" (a new photo of the photo-frame album every day) and an adjustable veil on the photo.
- Crash journal: the last fatal error is kept and shown in About (tablet and web page), with "Last start" and "Last crash" sensors in Home Assistant.
- Fix: a temperature just below zero was shown as "-0°".
- Settings no longer look empty after a Home Assistant restart: an entity list taken while Home Assistant is still starting is not kept, the web page asks again by itself, a current choice always stays visible in its list, and *None found* replaces *Connection required* when Home Assistant simply has no entity of that kind.
- Electricity: tick power or energy sensors on the web page; the first makes a chip on the home screen, and the chip (or the peak chip) opens a window with the coming peaks, the sensors and the last 24 hours.
- Calendar reminders: from an event's window, ask to be reminded 15 min or 1 h before, or the day before; the reminder is a notification with a sound and survives a restart.
- Grocery lists are sorted by aisle; long-press an item of a list to start a timer with its name.
- Hourly forecast: feels-like temperature and wind; a moon instead of the sun at night, on the weather card too.
- Timers: a warning with a "Turn it up" button when the tablet's alarm volume is at zero.
- Web page: "Refresh entities" button; the entity list is read again after every reconnection to Home Assistant. "Test the crash journal" in About.
- After a fatal error the app reopens by itself two seconds later instead of staying frozen behind Android's "has stopped" window.
- Shortcuts show in the room they belong to (the room of their Home Assistant area, or the one chosen on the web page); a shortcut with no room stays on the home screen.
- A room is always laid out in the same order: cameras, shortcuts, Now playing card, then the tiles.
- Home screen chips: choose on the web page which lights, locks, doors and windows count in "10 on", "2 doors open"…; a kind with nothing ticked shows no chip.
- Electricity chip: an energy meter shows today's consumption, or what the last reading added when it is read once a day ("+65 kWh · last reading"), instead of its running total. The tile of a power or energy sensor in a room opens the Electricity window.
- Now playing card: TV icon and app name for a show or a film; choose how long it stays after a pause (not at all, 10 or 30 minutes, or as long as the pause lasts); voice assistants are unticked by default.
- Whole-house overview: appliance doors and the door state published by a lock are unticked by default.
- A media player that is on but playing nothing shows "Inactive"; the weather card rounds the temperature to the degree.
- Tablet settings: the Display tab lists what is set on the web page.

## 2026.11.6

**Skins**

- Skins: a new way to draw the whole app while keeping the theme and wallpaper. **HomeKit** (the current look), **Minimal** (thin lines, light text, coloured icon and dot when on, outlined chips), **Paper** (light paper tinted by the theme, bordered cards, serif titles), **Pastel** (large soft cards in tones from the theme), **Neon** (dark, outlined, glowing in the theme's colour, monospaced digits), **Compact** (smaller, more devices per row) and **Large print** (very large high-contrast text, 3 tiles per row). Applies to the home screen, rooms, control windows, notifications, timers and This morning, not to the settings screens. Choose it on the tablet, the web page or with the new "Skin" select in Home Assistant. Each skin is one self-contained definition in the code, so adding one is simple.
- Night skin: an optional second skin with its own time range (e.g. Paper by day, Neon in the evening). The tablet switches by itself, never under your finger or while a window is open. On the tablet, the web page and in Home Assistant ("Night skin" select).
- Large print: the header keeps its chips on one row (alerts first) with a **+N** chip that opens the full list, so the rooms are visible again. Compact: long statuses are shortened (thermostat `20.5° → 21°`).

**Home screen**

- This morning: during a chosen time range, a ☀️ icon next to the bell (blue dot until read, back when a source changes) opens a full-screen panel with the sources you choose. No card on the home screen, so it takes no room.
  - News feeds: a title and the link of any RSS / Atom feed (XML), read directly by the tablet; stories in two columns with picture, title, summary and time; tap to expand; 4–12 stories; **Test** button on the web page; one-tap suggestions for Québec news sites. Tolerates feeds that are not strict XML; a feed that fails keeps its last stories.
  - Texts: an entity's state or attribute (Markdown), shown as cards side by side.
- Electricity peaks (e.g. Hydro-Québec): chip "Peak tomorrow / today" then red "Peak now · ends HH:MM", from a chosen calendar, with an optional reminder (30 min–3 h before) in the notification center.
- Weather alerts: chips from the alert-count sensors you pick (Environment Canada warnings, watches, advisories), with details on tap.
- Custom chips: entity + states + text + colour (e.g. "🧺 Washer to empty").
- Shortcuts: large buttons for chosen scripts, scenes and buttons on the home screen.
- The weather alerts window takes the height of its text instead of most of the screen.

**Notifications**

- Notification action buttons: up to 3 buttons (`actions`: title, service, entity_id, data) that call a Home Assistant service and mark the notification read.

**Settings**

- Settings reorganised the same way on the tablet and the web page: **Display** (appearance, dashboard, screen brightness and sleep, screen saver), **Sounds & alerts** (sounds and quiet hours, notifications, camera alerts), **Connection**, **Security**. New **Brightness** setting on the tablet and the web page (it was only in Home Assistant). Settings that depend on a switch are hidden (tablet) or greyed out (web) while it is off.
- Web page, Home tab reorganised like the home screen: quick links, then Header (who's home), Chips (collections, electricity peaks, weather alerts, custom chips), Weather and calendar, Shortcuts, This morning — one topic per card. The order of floors moved to the Rooms tab, under the list of rooms. Who's home lists only the companion-app people and per-person presence sensors, with the current selection on top; room detectors and tracked devices are folded under "Other sources" with a search field.
- Home Assistant entities follow the conventions: the tablet's settings are in **Configuration**, battery and technical values in **Diagnostic**; durations, memory (now in MB) and the power source (a list of values) have proper device classes. Home Assistant may ask once to confirm the unit change of "Memory used" in its statistics.

**Fixes**

- Fix: the app could close by itself when the connection to Home Assistant was cut while it was writing to it (switching to demo mode right after another change, or changing the server). The connection is now closed cleanly, and this network-library error can no longer close the app.
- Fix: a timer that ended while the settings or a camera were open rang with no visible way to stop it; the dashboard now comes back to the front with the card.
- Fix: changing the tablet's language no longer logs "Invalid option" errors in Home Assistant for the Theme, Wallpaper and Screen saver selects. Each language now has its own MQTT state topic for these lists, and the value is published before the list, so Home Assistant never reads a value in the other language.
- Fix: the "Screen shown" sensor kept the previous language after a language change.

**Also**

- Faster and lighter: the header is recalculated once per burst of sensor changes, saved lists are decoded once, and a command from Home Assistant no longer republishes every state.
- Demo mode shows examples of every new home-screen feature (peak, weather statement, collections, washer chip, This morning, shortcuts); help page updated with new screenshots.
- Demo mode also hides the MQTT server, account and device name, and no longer lets the example values be saved over the real ones.
- Under the hood: unit tests (feeds, notifications, time ranges, translations, readability of every skin) run on every push on GitHub; the main screen's code is split by feature; two unused translations removed.

## 2026.11.5

- Notification center: every notification shows for at most 30 s (configurable, 10 s–5 min; shorter if the automation asks). One not confirmed with **OK** stays unread: a bell with a red counter appears next to the time. Tapping the bell opens the list of unread notifications (newest first, with icon, title, message and time). Open one to read it (it is then marked as read), select several (or long-press) and mark them as read, or mark all as read. Unread notifications survive restarts and updates (50 at most).
- New Home Assistant entities: sensor "Unread notifications" and number "Notification duration".
- An emoji the tablet cannot draw (too recent for Android 8) is replaced by 💬 instead of an empty box.
- Notification tags: a notification with the same `tag` replaces the previous one, and the message `clear_notification` with that tag removes it from the tablet (like on phones), e.g. when Léon is back on his base.
- Quiet hours (default 22:00–07:00): no notification sound except red alerts, and the screen saver turns black. Switch "Quiet hours" in Home Assistant.
- Kitchen timers: timer button next to the time, quick durations (1 min to 1 h) or a custom one, several timers at once with +1 min, pause and cancel; the remaining time shows in the header; when one ends, an alarm rings (up to 2 min) with a large card until OK.
- Collection chips on the home screen: "Recycling tomorrow / today" (blue bin) and "Garbage tomorrow / today" (green bin), from a calendar and a keyword chosen for each on the web page (Home tab).
- Photo frame screen saver: photos from an Immich album or your favourites, read directly from Immich with an API key of the app (encrypted on the tablet), changing every 30 s (10 s–5 min), with the time and weather in a corner. Videos are skipped.
- Camera alerts also go to the notification center: title with the camera name, time, and a thumbnail of the camera captured a few seconds after the detection (no description). Open one to see the capture large. At most one entry per camera and type per minute; captures are deleted once read.
- Notification sounds switch: turn every notification sound off at all times (timers still ring). Also a switch in Home Assistant.
- Settings reorganised: a new **Sleep & sounds** tab (sleep, screen saver, sounds and quiet hours, notifications, camera alerts); the Display tab keeps appearance and dashboard options. The web page uses the same groups.
- When Home Assistant does not respond: the tiles are greyed out (last known state) under the "Offline · retrying…" banner, and tapping a device says the command can't be sent instead of doing nothing. The app reconnects by itself (tested: about 15 s after the network came back, MQTT a few seconds later).
- Help page (README): new sections for notifications (with a ready-made helper script), kitchen timers, and sleep / screen saver / sounds; new screenshots (notification center, timers, Sleep & sounds tab, collection chips).
- Fix: the agenda showed some events one day early ("Today" for tomorrow evening), and the forecast labelled tomorrow as "Today" in the evening.

## 2026.11.4

- Eufy cameras: the live view now turns on the camera's RTSP stream switch itself, waits for the stream address, and turns it back off when closed (the switches reset to off whenever the integration restarts).
- New camera alert: when a room camera detects a person (Eufy `event.<camera>_detection` with event_type person, or a `binary_sensor` person detector), its live view opens full screen for 20 s (10–60 s, setting in Display → Cameras and on the web page). The countdown starts with the first video frame; touching the screen keeps the live view open. Option "Trigger on: person only / any motion"; the banner says "Person detected" or "Motion detected".
- Doorbell alert: when someone rings a doorbell camera (`event.<camera>_doorbell` ring, or `binary_sensor.<camera>_ringing`), its live view opens with a "Someone is at the door" banner, whatever the person/motion setting. Can be turned off separately.
- Wake on motion (optional, off by default, only on tablets with a front camera): the front camera watches for someone in front of the tablet at very low resolution and prevents or ends dimming. Frames are analysed in memory and never saved or sent. Needs the camera permission, asked on the tablet.
- Notifications from Home Assistant (`notify.send_message` on the tablet's notify entity) now appear in a card centred over the dashboard: optional title, emoji icon, accent colour, camera snapshot, sound and duration (JSON message), countdown bar, OK button. A message received while another screen is open is shown on return.
- Screen saver choice: a black screen, or the time (no seconds), date and current weather, drifting slightly every minute to avoid burn-in. Adjustable brightness for the time-and-weather screen (1–60 %). Available in the tablet settings, on the web page and as Home Assistant entities (select "Screen saver", number "Screen saver brightness").
- Robot vacuums (`vacuum.*`): tile with state and battery (e.g. "Docked · 100 %"), new "Cleaning" category, and a control window with Start/Resume, Pause, Stop and Return to base; the current action is highlighted.
- Long device names wrap onto two lines on the tiles instead of being cut; on the web page, hovering a device shows its full name and ID.
- Tablet battery next to the time: percentage and a small battery icon (green with a bolt while charging, red under 20 %). Can be hidden in the settings, on the web page or from Home Assistant (switch "Show battery").
- Updates now live in the About tab (tablet and web page); the tablet settings tabs are Rooms, Display, Connection, Kiosk and About.
- Help page (README) updated with new screenshots: notifications, screen saver, vacuum controls, chip quick actions, About tab.

## 2026.11.3

- Home screen: the summary chips only show what needs attention (lights on, unlocked locks, open doors and windows).
- Tapping a chip lists those devices with a quick action button (turn off / on, lock / unlock); the list updates live.
- New About tab in the tablet settings and on the web page: version, build, GitHub links (repository, release notes, issues), tablet, Android and Home Assistant versions, connection status, uptime, license.

## 2026.11.2

- Fix: Wi-Fi updates now show Android's install confirmation (the installer's answer never reached the app on Android 8+).
- TileHouse reopens by itself after an update.
- An installation error is no longer hidden by the next automatic update check.

> 2026.11.1 cannot update itself over Wi-Fi: install 2026.11.2 manually once (download the APK and open it).

## 2026.11.1 — first public release

- HomeKit-style dashboard for Home Assistant: rooms, tiles, long-press controls (lights, fans, covers, thermostats, media).
- Home screen: greeting, who's home (person, device_tracker and room-presence sensors such as ESPresense/Bermuda),
  weather with 5-day forecast, calendar, house summary, rooms grouped by Home Assistant floors.
- Header chips for temperature, humidity, motion, doors, windows, leaks and locks, with details on tap.
- Cameras: snapshots, full-screen live view (RTSP or HLS), PTZ by swiping (PTZ buttons or ONVIF).
- Web configuration page on port 8080: rooms, devices, custom names, home screen, display, connection, PIN, backup/restore, updates.
- Tablet exposed to Home Assistant via MQTT discovery (battery, charging, screen, brightness, theme, notifications, update entity…).
- 18 season and holiday themes, 5 wallpapers.
- Updates over Wi-Fi: GitHub releases (checked every 6 h), Home Assistant update entity, or APK upload from the web page.
- Demo mode with a fictional house.
- English and French interface.
- Secrets encrypted with the Android Keystore.
