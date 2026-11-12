# R8 rules for the release build (minified). Libraries bring their own consumer rules; only what they miss
# is kept here. Check every change with a release build on a device - debug builds aren't minified.

# WorkManager's internal Room database is created by reflection (no-arg constructor of the generated
# *_Impl). work-runtime 2.10 with AGP 9's R8 strips that constructor -> crash at app start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Readable stack traces from user reports (map them with build/outputs/mapping/release/mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
