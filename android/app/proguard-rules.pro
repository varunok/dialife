# WorkManager (pulled in by Glance) creates its Room database by reflection;
# R8 cannot see that and strips the generated constructor.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# ...and its input mergers and workers too (Glance renders widgets in a worker).
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
