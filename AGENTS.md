# AGENTS.md

This document describes how AI development agents can work effectively with the CloudAmp codebase.

## Project Overview

CloudAmp is a native Android music player written in Kotlin that integrates with Spotify. The codebase follows a singleton-manager pattern with clear separation between API, authentication, playback, caching, and UI layers.

## Codebase Structure

```
cloudamp.app/
├── app/
│   ├── src/main/
│   │   ├── java/com/cloudamp/music/
│   │   │   ├── api/              # Spotify API client (Retrofit)
│   │   │   ├── auth/             # OAuth 2.0 PKCE authentication
│   │   │   ├── cache/            # Persistent library caching
│   │   │   ├── models/           # Data classes for API responses
│   │   │   ├── playback/         # PlaybackManager & MediaBrowserService
│   │   │   ├── ui/               # RecyclerView adapters
│   │   │   ├── MainActivity.kt   # Library browsing
│   │   │   ├── NowPlayingActivity.kt
│   │   │   └── SettingsActivity.kt
│   │   └── res/                  # Layouts, drawables, values
│   └── build.gradle              # App-level dependencies
├── build.gradle                  # Project-level config
├── settings.gradle
└── .github/workflows/            # CI/CD pipelines
```

## Key Files to Understand

When working on CloudAmp, these files are essential:

| File | Purpose | When to Modify |
|------|---------|----------------|
| `SpotifyApiService.kt` | API endpoint definitions | Adding new Spotify endpoints |
| `SpotifyApiClient.kt` | Retrofit client setup | Changing auth, timeouts, interceptors |
| `SpotifyModels.kt` | Data classes | Adding/modifying API response models |
| `PlaybackManager.kt` | Playback state singleton | Queue logic, transport controls |
| `CloudAmpService.kt` | Android Auto integration | Media browser content, notifications |
| `ExpandableLibraryAdapter.kt` | Main library UI | Library display, item types |
| `MainActivity.kt` | Library screen | User interactions, API calls |
| `NowPlayingActivity.kt` | Now playing screen | Playback UI updates |

## Development Patterns

### Singleton Managers

The app uses companion object singletons for shared state:

```kotlin
// Pattern used in SpotifyApiClient, PlaybackManager, LibraryCache
companion object {
    @Volatile
    private var instance: ClassName? = null

    fun getInstance(context: Context): ClassName {
        return instance ?: synchronized(this) {
            instance ?: ClassName(context).also { instance = it }
        }
    }
}
```

### Coroutine Usage

All async operations use Kotlin Coroutines:

```kotlin
private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

coroutineScope.launch {
    try {
        val result = withContext(Dispatchers.IO) {
            apiClient.someApiCall()
        }
        // Update UI on Main thread
    } catch (e: Exception) {
        // Handle error
    }
}
```

### API Calls

Spotify API calls follow this pattern:

```kotlin
// In SpotifyApiService.kt - Define endpoint
@GET("v1/me/player")
suspend fun getPlaybackState(): Response<PlaybackStateResponse>

// In consuming code - Call with error handling
val response = apiClient.service.getPlaybackState()
if (response.isSuccessful) {
    response.body()?.let { state ->
        // Use the data
    }
}
```

### RecyclerView Adapters

The library uses a multi-type adapter pattern:

```kotlin
sealed class LibraryItem {
    data class ArtistItem(val artist: Artist) : LibraryItem()
    data class AlbumItem(val album: Album) : LibraryItem()
    data class TrackItem(val track: Track) : LibraryItem()
    // ... more types
}

override fun getItemViewType(position: Int): Int {
    return when (items[position]) {
        is LibraryItem.ArtistItem -> VIEW_TYPE_ARTIST
        is LibraryItem.AlbumItem -> VIEW_TYPE_ALBUM
        // ...
    }
}
```

## Common Tasks

### Adding a New Spotify API Endpoint

1. Add the endpoint to `SpotifyApiService.kt`:
   ```kotlin
   @GET("v1/new/endpoint")
   suspend fun newEndpoint(): Response<NewResponse>
   ```

2. Create response model in `SpotifyModels.kt`:
   ```kotlin
   data class NewResponse(
       @SerializedName("field_name")
       val fieldName: String
   )
   ```

3. Call from activity/manager with error handling

### Adding Android Auto Content

1. Define media ID constants in `CloudAmpService.kt`
2. Handle the ID in `onLoadChildren()`:
   ```kotlin
   NEW_CONTENT_ID -> {
       val items = loadNewContent()
       result.sendResult(items)
   }
   ```

3. Create `MediaBrowserCompat.MediaItem` objects with proper flags

### Modifying Playback Behavior

1. Update `PlaybackManager.kt` for queue/state logic
2. Update `CloudAmpService.kt` MediaSession callbacks
3. Ensure MediaSession metadata is updated for notifications

### Adding UI Elements

1. Create/modify layout XML in `res/layout/`
2. Follow the green-on-black color scheme from `res/values/colors.xml`
3. Use consistent padding (8dp/10dp increments)
4. Bind views in activity using `findViewById` or ViewBinding

## Testing Changes

### Local Testing

```bash
# Build and install debug APK
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat | grep -E "(CloudAmp|Spotify)"
```

### Android Auto Testing

1. Enable Developer Mode in Android Auto app
2. Use "Desktop Head Unit" (DHU) for emulator testing
3. Or test directly in a vehicle with Android Auto

## Code Style Guidelines

- **Naming**: camelCase for functions/variables, PascalCase for classes
- **Indentation**: 4 spaces
- **Max line length**: ~120 characters
- **Comments**: Only where logic is non-obvious
- **Nullability**: Use Kotlin null-safety, avoid `!!` where possible
- **Error handling**: Always handle API response failures gracefully

## Dependencies

Key dependencies to be aware of:

| Library | Version | Usage |
|---------|---------|-------|
| Retrofit | 2.9.0 | REST API client |
| OkHttp | 4.12.0 | HTTP client |
| Glide | 4.16.0 | Image loading |
| Coroutines | 1.7.3 | Async operations |
| AndroidX Media | 1.7.0 | MediaSession/MediaBrowser |

## Gotchas and Edge Cases

1. **Token Refresh**: The API client automatically refreshes tokens on 401. Don't add manual refresh logic.

2. **Active Device**: Spotify playback requires an active device. The app opens Spotify as fallback.

3. **Album Types**: Albums are categorized by `album_type` field AND track count (single-track albums = singles).

4. **Pagination**: Spotify APIs are paginated. Use cursor-based pagination for `/me/following`.

5. **Android Auto**: Content changes require calling `notifyChildrenChanged()` on the service.

6. **MediaSession**: Always update metadata when track changes for lock screen/notification display.

## Useful Commands

```bash
# Get version info
./gradlew printVersionCode
./gradlew printVersionName

# Clean build
./gradlew clean assembleDebug

# Check for dependency updates
./gradlew dependencyUpdates

# Lint check
./gradlew lint
```

## Branch Workflow

- `main` - Production branch, CI builds APKs
- `claude/*` - AI agent development branches
- Feature branches merge to `main` via pull request

## Resources

- [Spotify Web API Reference](https://developer.spotify.com/documentation/web-api/reference)
- [Android MediaBrowserService Guide](https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Retrofit Documentation](https://square.github.io/retrofit/)
