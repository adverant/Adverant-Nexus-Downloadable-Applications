# sherpa-onnx JNI — keep native methods
-keep class com.k2fsa.sherpa.onnx.** { *; }

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# Keep our TTS classes (reflection in service binding)
-keep class ai.adverant.prosecreator.tts.** { *; }
