# Firebase ships its own consumer rules; the adapter itself only needs its public API preserved.
-keep class io.paykit.firebase.FirebaseConfigSource { public *; }
