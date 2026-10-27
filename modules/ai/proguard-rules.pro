# LiteRT-LM calls into Kotlin classes from JNI and uses Gson reflection.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.errorprone.annotations.**
