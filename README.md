# Radio Streaming App

A simple Android app that streams internet radio stations with advanced buffer control and skip functionality.

## Features

- **Multiple Radio Stations**: Pre-configured list of popular radio stations
- **Advanced Streaming**: Uses ExoPlayer for robust audio streaming
- **Buffer Control**: Real-time buffer percentage display and skip functionality
- **Playback Controls**: Play, pause, stop controls
- **Background Playback**: Continues playing in background with notification
- **Skip Buffered Content**: Unique feature to skip ahead in buffered audio

## Technical Details

- **Framework**: Native Android with Kotlin
- **Audio Engine**: Google ExoPlayer 2.19.1
- **Architecture**: Service-based background playback
- **Minimum SDK**: API 24 (Android 7.0)
- **Target SDK**: API 34 (Android 14)

## Key Components

### RadioService.kt
- Background service handling audio playback
- ExoPlayer integration with custom buffer monitoring
- Notification system for background playback
- Buffer skip functionality

### MainActivity.kt
- Main UI with radio station list
- Playback controls and buffer status display
- Service binding and communication

### Buffer Skip Feature
The app includes a unique "Skip Buffer" functionality that allows users to:
- Skip ahead in buffered content for live streams
- Restart the stream to get the latest content
- Useful for reducing delay in live radio broadcasts

## How to Build

1. **Prerequisites**:
   - Android Studio Arctic Fox or later
   - JDK 8 or higher
   - Android SDK with API 34

2. **Setup**:
   ```bash
   git clone <repository-url>
   cd RadioApp
   ```

3. **Build**:
   - Open project in Android Studio
   - Let Gradle sync complete
   - Build → Make Project
   - Run on device or emulator

## How to Use

1. **Select Station**: Tap any radio station from the list
2. **Play**: Press play button to start streaming
3. **Monitor Buffer**: Watch the buffer percentage and progress bar
4. **Skip Buffer**: Use "Skip Buffer" button to jump ahead in buffered content
5. **Background Play**: App continues playing when minimized

## Radio Stations Included

- BBC Radio 1 (Pop)
- Classic FM (Classical) 
- Jazz FM (Jazz)
- Rock FM (Rock)
- Chill Radio (Ambient)

## Permissions Required

- `INTERNET`: For streaming radio content
- `ACCESS_NETWORK_STATE`: For checking network connectivity
- `FOREGROUND_SERVICE`: For background playback
- `WAKE_LOCK`: To keep device awake during playback

## Customization

To add your own radio stations, edit the `radioStations` list in `MainActivity.kt`:

```kotlin
RadioStation(id, "Station Name", "http://stream-url", "Genre")
```

## Troubleshooting

- **No Sound**: Check internet connection and stream URL validity
- **Buffering Issues**: Try the "Skip Buffer" feature or restart the stream
- **App Crashes**: Ensure proper permissions are granted

## Future Enhancements

- Favorites system
- Sleep timer
- Equalizer integration
- Custom station addition UI
- Recording functionality