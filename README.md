# CloudAmp

A Winamp-inspired Android music player that integrates with Spotify for library browsing and playback control, with full Android Auto support.

![Android](https://img.shields.io/badge/Android-8.0%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue)
![License](https://img.shields.io/badge/License-Private-red)

## Features

### Spotify Integration
- **OAuth 2.0 PKCE Authentication** - Secure login with automatic token refresh
- **Library Access** - Browse followed artists, saved albums, top tracks, and playlists
- **Playback Control** - Full remote control of Spotify playback from the app
- **Real-time State** - Synchronized playback status with Spotify

### Library Management
- **Hierarchical Browsing** - Artists → Albums → Tracks with expandable lists
- **Album Categorization** - Automatically organized into LPs, EPs, and Singles
- **Saved Albums** - Heart symbol (♥) indicates albums in your library
- **Alphabetical Sections** - Quick navigation with letter headers
- **Search** - Find artists, albums, and tracks quickly

### Playback Features
- **Queue Management** - View and navigate the current play queue
- **Transport Controls** - Play, pause, skip, previous, seek
- **Now Playing** - Full-screen view with album art and progress
- **Album Queuing** - Selecting a track queues the entire album from that point

### Android Auto
- **Full Media Browser** - Browse your library from your car's display
- **Voice Search** - Find music with voice commands
- **Playback Controls** - All standard car audio controls
- **Album Art** - Beautiful display on car screens
- **Queue Building** - Plays full albums when selecting tracks

### Design
- **Winamp-Inspired Theme** - Retro dark UI with green accents
- **Cyberpunk Aesthetic** - Black backgrounds with bright green text
- **Equalizer Icon** - Classic visualizer-style app icon

## Screenshots

*Coming soon*

## Requirements

- **Android 8.0** (API 26) or higher
- **Spotify Premium** account (required for playback control)
- **Spotify App** installed on device (for active playback device)

## Installation

### From GitHub Actions

1. Go to the [Actions tab](../../actions) in this repository
2. Click on the latest successful "Build CloudAmp APK" workflow
3. Download the APK from the Artifacts section
4. Install on your Android device (enable "Install from unknown sources" if needed)

### Building from Source

#### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Gradle 8.2.1

#### Steps

```bash
# Clone the repository
git clone https://github.com/chris-albert/cloudamp.app.git
cd cloudamp.app

# Build debug APK
./gradlew assembleDebug

# APK location
ls app/build/outputs/apk/debug/app-debug.apk
```

## Setup

### Spotify Developer Credentials

1. Go to [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
2. Create a new application
3. Add redirect URI: `cloudamp://callback`
4. Note your **Client ID** and **Client Secret**

### App Configuration

1. Open CloudAmp app
2. Tap the settings/gear icon
3. Enter your Client ID and Client Secret
4. Tap "Login with Spotify"
5. Authorize the app in your browser
6. Your library will load automatically

### Required Spotify Scopes

CloudAmp requests the following permissions:
- `user-read-playback-state` - Read current playback
- `user-modify-playback-state` - Control playback
- `user-follow-read` - Access followed artists
- `user-library-read` - Access saved albums/tracks
- `user-top-read` - Access top artists/tracks
- `streaming` - Control Spotify playback
- `playlist-read-private` - Access private playlists
- `playlist-read-collaborative` - Access collaborative playlists

## Architecture

### Project Structure

```
app/src/main/java/com/cloudamp/music/
├── MainActivity.kt              # Library browsing screen
├── NowPlayingActivity.kt        # Playback control screen
├── SettingsActivity.kt          # OAuth and settings
│
├── api/
│   ├── SpotifyApiClient.kt      # Retrofit client with auto-refresh
│   └── SpotifyApiService.kt     # API endpoint definitions
│
├── auth/
│   ├── SpotifyAuthManager.kt    # OAuth PKCE flow
│   └── SpotifyCallbackActivity.kt
│
├── playback/
│   ├── PlaybackManager.kt       # Playback state & queue
│   └── CloudAmpService.kt       # MediaBrowserService (Android Auto)
│
├── models/
│   └── SpotifyModels.kt         # Data classes
│
├── cache/
│   └── LibraryCache.kt          # Persistent library cache
│
└── ui/
    ├── ExpandableLibraryAdapter.kt
    └── QueueAdapter.kt
```

### Key Components

| Component | Purpose |
|-----------|---------|
| `SpotifyApiClient` | Singleton Retrofit client with OAuth token management |
| `PlaybackManager` | Singleton managing queue, playback state, and MediaSession |
| `CloudAmpService` | MediaBrowserService for Android Auto integration |
| `LibraryCache` | SharedPreferences-based persistent cache |
| `SpotifyAuthManager` | OAuth 2.0 PKCE flow handler |

### Technology Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 1.9.0 |
| UI Framework | Android Views + RecyclerView |
| Networking | Retrofit 2.9.0 + OkHttp 4.12.0 |
| Async | Kotlin Coroutines 1.7.3 |
| Image Loading | Glide 4.16.0 |
| Media | AndroidX Media 1.7.0 |
| Build | Gradle 8.2.1 + AGP 8.1.0 |

## Development

### Version Numbering

Version codes are generated dynamically based on build timestamp:
- Format: `MMddHHmm` (e.g., `01281430` for Jan 28, 2:30 PM)
- Version name: `1.0.{versionCode}`

### Gradle Tasks

```bash
# Print current version
./gradlew printVersionCode
./gradlew printVersionName

# Build debug APK
./gradlew assembleDebug

# Run lint checks
./gradlew lint
```

### CI/CD

GitHub Actions automatically builds APKs on:
- Push to `main` branch
- Pull requests to `main`
- Manual workflow dispatch

APK artifacts are retained for 30 days.

## Android Auto Setup

CloudAmp automatically appears in Android Auto once installed. To enable:

1. Connect your phone to your car's Android Auto
2. Open the app drawer in Android Auto
3. Select "CloudAmp"
4. Browse and play your Spotify library

### Browsable Content

- **Top Tracks** - Your most played tracks
- **Artists** - All followed artists with expandable albums
- **Saved Albums** - Albums saved to your library

## Troubleshooting

### "No active device" error

Spotify requires an active playback device. Solutions:
1. Open Spotify app and start playing any track
2. CloudAmp will then be able to control playback

### Token refresh issues

If playback stops working:
1. Go to Settings
2. Tap "Reload Library"
3. If still broken, clear credentials and log in again

### Library not loading

1. Check internet connection
2. Verify Spotify credentials are valid
3. Try "Reload Library" in settings

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Spotify API communication |
| `FOREGROUND_SERVICE` | Background playback |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media service |
| `WAKE_LOCK` | Prevent sleep during playback |
| `POST_NOTIFICATIONS` | Playback notifications |

## Security

- **PKCE OAuth** - Secure authentication without exposing secrets
- **Token Refresh** - Automatic, transparent token renewal
- **No Hardcoded Credentials** - All secrets provided by user
- **Private Storage** - Tokens stored in app-private SharedPreferences

## Contributing

See [AGENTS.md](AGENTS.md) for information on using AI development agents to contribute to this project.

## License

Private repository - All rights reserved.

## Jellyfin API Reference

CloudAmp uses the following Jellyfin REST API endpoints. All authenticated requests require the `Authorization` header:

```
Authorization: MediaBrowser Client="CloudAmp", Device="<device>", DeviceId="<id>", Version="1.0", Token="<access_token>"
```

Replace `SERVER` with your Jellyfin server URL (e.g. `https://jellyfin.example.com`).

### Authentication

**Authenticate by username/password** — returns an access token and user info.

```bash
curl -X POST "SERVER/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0"' \
  -d '{"Username": "chris", "Pw": "secret"}'
```

**Get current user** — validate token and retrieve user profile.

```bash
curl "SERVER/Users/Me" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

### Artists

**Get album artists** — paginated list of album artists in the library.

```bash
curl "SERVER/Artists/AlbumArtists?UserId=USER_ID&SortBy=SortName&SortOrder=Ascending&Recursive=true&Fields=PrimaryImageAspectRatio,SortName,ChildCount,Path&StartIndex=0&Limit=500" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

### Albums

**Get albums by artist (folder structure)** — albums nested under an artist's folder.

```bash
curl "SERVER/Users/USER_ID/Items?ParentId=ARTIST_ID&IncludeItemTypes=MusicAlbum&SortBy=ProductionYear,SortName&SortOrder=Descending&Recursive=true&Fields=PrimaryImageAspectRatio,ProductionYear" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Get albums by artist (metadata)** — fallback using AlbumArtistIds metadata.

```bash
curl "SERVER/Users/USER_ID/Items?AlbumArtistIds=ARTIST_ID&IncludeItemTypes=MusicAlbum&SortBy=ProductionYear,SortName&SortOrder=Descending&Recursive=true&Fields=PrimaryImageAspectRatio,ProductionYear" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Get all albums** — bulk-fetch all albums in the library (paginated).

```bash
curl "SERVER/Users/USER_ID/Items?IncludeItemTypes=MusicAlbum&SortBy=ProductionYear,SortName&SortOrder=Descending&Recursive=true&Fields=PrimaryImageAspectRatio,ProductionYear,AlbumArtists&StartIndex=0&Limit=500" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Recently added albums** — sorted by date added to library.

```bash
curl "SERVER/Users/USER_ID/Items?SortBy=DateCreated&SortOrder=Descending&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=50&Fields=PrimaryImageAspectRatio,ProductionYear" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Random albums** — for discovery/shuffle.

```bash
curl "SERVER/Users/USER_ID/Items?SortBy=Random&IncludeItemTypes=MusicAlbum&Recursive=true&Limit=10&Fields=PrimaryImageAspectRatio,ProductionYear" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

### Tracks

**Get album tracks** — tracks in a specific album.

```bash
curl "SERVER/Users/USER_ID/Items?ParentId=ALBUM_ID&IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber&SortOrder=Ascending&Fields=PrimaryImageAspectRatio,Artists" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Get all tracks** — bulk-fetch all tracks in the library (paginated).

```bash
curl "SERVER/Users/USER_ID/Items?IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber&SortOrder=Ascending&Recursive=true&Fields=PrimaryImageAspectRatio,Artists,AlbumId&StartIndex=0&Limit=500" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Recently played tracks** — tracks sorted by last played date.

```bash
curl "SERVER/Users/USER_ID/Items?SortBy=DatePlayed&SortOrder=Descending&IncludeItemTypes=Audio&Filters=IsPlayed&Recursive=true&Limit=200&Fields=AlbumId,Album,AlbumArtist,ProductionYear,PrimaryImageAspectRatio" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Mark track as played**

```bash
curl -X POST "SERVER/Users/USER_ID/PlayedItems/ITEM_ID" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

### Playlists

**Get all playlists**

```bash
curl "SERVER/Users/USER_ID/Items?IncludeItemTypes=Playlist&SortBy=SortName&SortOrder=Ascending&Recursive=true&Fields=PrimaryImageAspectRatio,ChildCount" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

**Get playlist items**

```bash
curl "SERVER/Playlists/PLAYLIST_ID/Items?UserId=USER_ID&Fields=PrimaryImageAspectRatio,Artists" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

### Search

**Search across artists, albums, and tracks**

```bash
curl "SERVER/Users/USER_ID/Items?SearchTerm=radiohead&IncludeItemTypes=MusicArtist,MusicAlbum,Audio&Recursive=true&Limit=50&Fields=PrimaryImageAspectRatio,Artists" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"'
```

### Streaming & Images

**Stream audio** — universal audio endpoint used by ExoPlayer (auth via header).

```bash
curl "SERVER/Audio/ITEM_ID/universal?audioCodec=aac&container=mp3,aac,opus,flac|aac,flac" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"' \
  --output track.mp3
```

**Get item image** — primary artwork for artists, albums, or tracks.

```bash
curl "SERVER/Items/ITEM_ID/Images/Primary?maxWidth=300" \
  -H 'Authorization: MediaBrowser Client="CloudAmp", Device="Pixel", DeviceId="abc123", Version="1.0", Token="TOKEN"' \
  --output cover.jpg

# Or with API key in URL (used by Android Auto / Glide):
curl "SERVER/Items/ITEM_ID/Images/Primary?maxWidth=300&api_key=TOKEN" \
  --output cover.jpg
```

## Acknowledgments

- Inspired by [Winamp](https://www.winamp.com/) - the classic media player
- Built with the [Spotify Web API](https://developer.spotify.com/documentation/web-api)
- Android Auto integration via [MediaBrowserService](https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice)
