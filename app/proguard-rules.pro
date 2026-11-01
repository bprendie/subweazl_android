# WorkManager opens the generated Room database by reflection. Preserve its
# constructor under AGP 9 / R8 full-mode optimization.
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
# Persisted WorkManager jobs refer to this class by its fully-qualified name.
-keep class io.prendie.subweazl.AudioDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
