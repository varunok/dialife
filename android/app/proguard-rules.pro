# WorkManager (pulled in by Glance) creates its Room database by reflection;
# R8 cannot see that and strips the generated constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# ...and its input mergers and workers too (Glance renders widgets in a worker).
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
# ML Kit's code scanner finds its parts by reflection; stripped, it crashes on start
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
