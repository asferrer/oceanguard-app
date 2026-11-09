# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# llama.cpp JNI bridge — keep JNI-called Kotlin classes and callback interface
-keep class com.oceanguard.ai.inference.LlamaCppBridge { *; }
-keep interface com.oceanguard.ai.inference.TokenStreamCallback { *; }
-keepclassmembers interface com.oceanguard.ai.inference.TokenStreamCallback {
    public void onToken(java.lang.String);
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep data classes (for JSON parsing)
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.oceanguard.ai.data.** { *; }

# Keep MapLibre native classes
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# TensorFlow Lite GPU — classes referenced but stripped by litert-api exclusion
-dontwarn org.tensorflow.lite.gpu.**

# LiteRT-LM — Gemma 4 E2B on-device inference
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# The SDK finds @Tool methods by reflection, so R8 sees them as unused and strips them,
# which leaves the zone report with no tools in release builds.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature
-keep class * implements com.google.ai.edge.litertlm.ToolSet {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# WorkManager + DataContributionWorker (WorkManager builds workers by reflection from their class name)
-keep class androidx.work.** { *; }
-keep class com.oceanguard.service.DataContributionWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.oceanguard.data.contribution.** { *; }
-keep class com.oceanguard.utils.CocoAnnotationSerializer { *; }
# Firebase SDKs ship their own consumer rules; nothing to add when App Check is on.

# ONNX Runtime (species detector and embedder): native code calls back into these classes through JNI.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Android Keystore (device key and attestation) is a framework API reached through JCA providers,
# so it needs no rule; keep the signer classes above because they are matched by name in the envelope.

# Gson reads these species pack classes by reflection; outside com.oceanguard.ai.data they were renamed and
# stripped, so a downloaded pack failed to load in release builds (ClassCastException in
# MarineRegionResolver.loadHierarchy, empty species_pack.json) and the app asked for the download again.
-keep class com.oceanguard.ai.inference.species.SpeciesPackConfig { *; }
-keep class com.oceanguard.ai.inference.species.SpeciesPackConfig$* { *; }
-keep class com.oceanguard.ai.inference.species.MarineRegionResolver$HierarchyEntry { *; }
