# Firebase ships its own consumer rules; the sink itself only needs its public API preserved.
-keep class io.trackkit.firebase.FirebaseSink { public *; }
