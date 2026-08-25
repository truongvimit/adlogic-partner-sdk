# Firebase ships its own consumer rules; these classes only need their public API preserved.
# Each is instantiated by name from host code, so R8 must not rename or strip them.
-keep class io.suite.firebase.FirebaseSink { public *; }
-keep class io.suite.firebase.FirebaseConfigSource { public *; }
-keep class io.suite.firebase.FirebaseAdConfigSource { public *; }

# The two config sources implement ports from :paykit and :ads, both compileOnly here — so this
# module ships all three classes even to a consumer that declares only one kit. R8 resolves the
# supertype of a kept class, and an IAP-only app (:billingkit + :paykit + this, no :ads) fails
# `minifyReleaseWithR8` on AdConfigSource without the first line. The second is its mirror for an
# ads-only app; that direction builds without it today, but the asymmetry is R8's, not a contract.
-dontwarn com.ads.module.config.AdConfigSource
-dontwarn io.paykit.config.PaywallConfigSource
