# Changelog

TileHouse uses calendar versioning: `YEAR.MONTH.N`.

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
