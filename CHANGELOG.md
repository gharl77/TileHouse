# Changelog

TileHouse uses calendar versioning: `YEAR.MONTH.N`.

## 2026.11.6 (in progress)

- Electricity peaks (e.g. Hydro-Québec): chip "Peak tomorrow / today" then red "Peak now · ends HH:MM", from a chosen calendar, with an optional reminder (30 min–3 h before) in the notification center.
- Weather alerts: chips from the alert-count sensors you pick (Environment Canada warnings, watches, advisories), with details on tap.
- Custom chips: entity + states + text + colour (e.g. "🧺 Washer to empty").
- Shortcuts: large buttons for chosen scripts, scenes and buttons on the home screen.
- Morning card: during a chosen time range, a card with the sources you choose (any entity state/attribute as Markdown text, or a news feed's headlines); the web page suggests summaries and feeds it finds; tap to read everything.
- Notification action buttons: up to 3 buttons (`actions`: title, service, entity_id, data) that call a Home Assistant service and mark the notification read.
- Fix: changing the tablet's language no longer logs "Invalid option" errors in Home Assistant for the Theme, Wallpaper and Screen saver selects. Each language now has its own MQTT state topic for these lists, and the value is published before the list, so Home Assistant never reads a value in the other language.

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
