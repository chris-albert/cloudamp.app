# CloudAmp ProGuard Rules

# ── Gson (reflection-based serialization) ──────────────────────────
# Keep all data classes that Gson deserializes via reflection
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep model classes used with Gson
-keep class com.cloudamp.music.models.** { *; }
-keep class com.cloudamp.music.api.DriveFile { *; }
-keep class com.cloudamp.music.api.DriveFileListResponse { *; }
-keep class com.cloudamp.music.api.DriveAboutResponse { *; }
-keep class com.cloudamp.music.api.DriveUser { *; }
-keep class com.cloudamp.music.api.StartPageTokenResponse { *; }
-keep class com.cloudamp.music.api.DriveChange { *; }
-keep class com.cloudamp.music.api.DriveChangeListResponse { *; }
-keep class com.cloudamp.music.api.GDriveArtist { *; }
-keep class com.cloudamp.music.api.GDriveAlbum { *; }
-keep class com.cloudamp.music.api.GDriveTrack { *; }
-keep class com.cloudamp.music.cache.PlaybackStateStore$LastPlaybackState { *; }
-keep class com.cloudamp.music.cache.PersistedFavorites { *; }
-keep class com.cloudamp.music.cache.PendingToggle { *; }
-keep class com.cloudamp.music.cache.FavoritesCore$FavoriteEntry { *; }

# Gson TypeToken requires generic signature preservation
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit ───────────────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# ── OkHttp ─────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── ExoPlayer ──────────────────────────────────────────────────────
-dontwarn com.google.android.exoplayer2.**

# ── Firebase Crashlytics ───────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# ── AndroidX / Kotlin ──────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn kotlinx.coroutines.**
