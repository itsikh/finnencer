# Add project specific ProGuard rules here.

# ──────────────────────────────────────────────────────────────────────────────
# Gson + Kotlin metadata
# Without these, R8 strips @SerializedName + Kotlin's @Metadata annotation,
# which means data-class fields lose their JSON names ("max_tokens" becomes
# "maxTokens") and nullable types can blow up with ClassCastException during
# reflective deserialization. Symptoms in release APK only.
# ──────────────────────────────────────────────────────────────────────────────

# Keep generic signatures + annotations (Gson needs them for reflection)
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Don't strip @SerializedName etc.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}

# Keep our wire-format DTOs intact (Finnhub, Anthropic, Gemini, EDGAR, RSS).
-keep class io.itsikh.finnencer.data.api.** { *; }

# Keep Room/Compose-side entities (Gson serializes some of these into the QR
# bundle + cost meter).
-keep class io.itsikh.finnencer.data.entity.** { *; }
-keep class io.itsikh.finnencer.data.dao.ScoredArticleRow { *; }
-keep class io.itsikh.finnencer.data.dao.ProviderUsageRow { *; }

# Kotlin metadata — required for reflection-based libs (Gson)
-keep class kotlin.Metadata { *; }

# Keep enum value()/values() so name-based serialization works.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ──────────────────────────────────────────────────────────────────────────────
# Retrofit / OkHttp
# ──────────────────────────────────────────────────────────────────────────────

# Retrofit uses generics for service interfaces; need Signature + Continuation.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Service interfaces — keep method signatures so Retrofit can build proxies.
-keep class io.itsikh.finnencer.data.api.FinnhubService { *; }
-keep class io.itsikh.finnencer.data.api.AnthropicService { *; }
-keep class io.itsikh.finnencer.data.api.GeminiService { *; }
-keep class io.itsikh.finnencer.data.api.SecEdgarService { *; }
-keep class io.itsikh.finnencer.data.api.RssService { *; }

# OkHttp internals — defensive.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ──────────────────────────────────────────────────────────────────────────────
# Hilt / Dagger
# ──────────────────────────────────────────────────────────────────────────────
-dontwarn com.google.errorprone.annotations.**

# ──────────────────────────────────────────────────────────────────────────────
# ML Kit / CameraX (defensive — no symptoms yet, but they use reflection)
# ──────────────────────────────────────────────────────────────────────────────
-dontwarn com.google.mlkit.**
-dontwarn androidx.camera.**
