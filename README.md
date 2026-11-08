# AppTracker

AppTracker is a native Android app for reviewing a 24-hour phone-activity timeline:
- Foreground apps with start/end times
- Home-screen periods
- Screen-off periods
- Lock-screen periods
- A daily usage chart and date-based history

## Requirements
- Android 8.0 (API 26) or newer
- Usage access granted by the user in Android Settings

## Build
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- Java 17
- Kotlin 2.0.21
- Jetpack Compose, Room, and WorkManager

Usage events are read from Android's UsageStatsManager and stored locally in Room. A periodic background worker refreshes the current and previous day. Android and device-vendor background restrictions can delay collection; data before AppTracker was installed or before Android retains events may not be reconstructable.

Pushes to `development` trigger the GitHub Actions APK build.
