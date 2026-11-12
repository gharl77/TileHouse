# Changelog

TileHouse uses calendar versioning: `YEAR.MONTH.N`.

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
