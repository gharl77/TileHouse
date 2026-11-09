# Changelog

TileHouse uses calendar versioning: `YEAR.MONTH.N`.

## 2026.11.4 (in progress)

- Eufy cameras: the live view now turns on the camera's RTSP stream switch itself, waits for the stream address, and turns it back off when closed (the switches reset to off whenever the integration restarts).
- New camera alert: when a room camera detects a person (Eufy `event.<camera>_detection` with event_type person, or a `binary_sensor` person detector), its live view opens full screen for 20 s (10–60 s, setting in Display → Cameras and on the web page). The countdown starts with the first video frame; touching the screen keeps the live view open. Option "Trigger on: person only / any motion"; the banner says "Person detected" or "Motion detected".

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
