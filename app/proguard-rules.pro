# Gson models are read by reflection
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.anees.novamobile.data.** { *; }
-keep class com.anees.novamobile.download.DownloadMeta { *; }
-keep class com.anees.novamobile.download.DownloadStarter$Item { *; }
-keep class com.anees.novamobile.data.api.TmdbSearchResult { *; }
-keep class com.anees.novamobile.ui.player.WebStreamSniffer$Sniffed { *; }
-keep class com.anees.novamobile.data.local.Settings { *; }
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Retrofit + coroutines under R8 full mode
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-keep interface com.anees.novamobile.data.api.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class com.anees.novamobile.sync.Sync$Pending { *; }
