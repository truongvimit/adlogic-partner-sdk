package io.trackkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Compiles the constructors Java hosts already use alongside the optional attempt-aware API. */
public class AdLoadSchemaJavaTest {

    @Test
    public void legacyLoadConstructorsRemainAvailable() {
        TrackkitEvents.Ad.Request request = new TrackkitEvents.Ad.Request("home", AdFormat.NATIVE, "unit");
        TrackkitEvents.Ad.Loaded loaded = new TrackkitEvents.Ad.Loaded("home", AdFormat.NATIVE, "unit", 12L);
        TrackkitEvents.Ad.LoadFailed failed = new TrackkitEvents.Ad.LoadFailed("home", AdFormat.NATIVE, "unit", 3);

        assertEquals("ad_request", request.getName());
        assertEquals(12L, loaded.getParams().get("latency_ms"));
        assertEquals(3, failed.getParams().get("error_code"));
    }

    @Test
    public void compiledKotlinLoadedCallRetainsItsOriginalDefaultConstructor() throws Exception {
        TrackkitEvents.Ad.Loaded loaded = TrackkitEvents.Ad.Loaded.class.getConstructor(
                String.class, AdFormat.class, String.class, Long.class,
                int.class, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        ).newInstance("home", AdFormat.NATIVE, "unit", 99L, 8, null);

        assertEquals("ad_loaded", loaded.getName());
        assertEquals("home", loaded.getParams().get("placement"));
        assertEquals("native", loaded.getParams().get("ad_format"));
        assertEquals("unit", loaded.getParams().get("ad_unit_id"));
        assertNull(loaded.getParams().get("latency_ms"));
        assertNull(loaded.getParams().get("attempt_id"));
    }

    @Test
    public void compiledKotlinLoadFailedCallRetainsItsOriginalDefaultConstructor() throws Exception {
        TrackkitEvents.Ad.LoadFailed failed = TrackkitEvents.Ad.LoadFailed.class.getConstructor(
                String.class, AdFormat.class, String.class, Integer.class,
                int.class, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        ).newInstance("home", AdFormat.NATIVE, "unit", 99, 8, null);

        assertEquals("ad_load_failed", failed.getName());
        assertEquals("home", failed.getParams().get("placement"));
        assertEquals("native", failed.getParams().get("ad_format"));
        assertEquals("unit", failed.getParams().get("ad_unit_id"));
        assertNull(failed.getParams().get("error_code"));
        assertNull(failed.getParams().get("latency_ms"));
        assertNull(failed.getParams().get("attempt_id"));
    }

    @Test
    public void javaRequestCanCarryTheLogicalAttemptId() {
        TrackkitEvents.Ad.Request request =
                new TrackkitEvents.Ad.Request("home", AdFormat.NATIVE, "unit", "attempt-1");

        assertEquals("attempt-1", request.getParams().get("attempt_id"));
    }

    @Test
    public void javaTerminalsAndTierResultsCarryTheAttemptWithoutReplacingLegacyArguments() {
        TrackkitEvents.Ad.Loaded loaded =
                new TrackkitEvents.Ad.Loaded("home", AdFormat.NATIVE, "winner", 42L, "attempt-1");
        TrackkitEvents.Ad.LoadFailed failed =
                new TrackkitEvents.Ad.LoadFailed("home", AdFormat.NATIVE, "last", 3, 67L, "attempt-2");
        TrackkitEvents.Ad.TierResult tier =
                new TrackkitEvents.Ad.TierResult("home", AdFormat.NATIVE, "first", "attempt-1", 1, "load_failed", 3, 20L);
        TrackkitEvents.Ad.TierResult optionalTier =
                new TrackkitEvents.Ad.TierResult("home", AdFormat.NATIVE, "winner", "attempt-1", 2, "loaded");

        assertEquals("attempt-1", loaded.getParams().get("attempt_id"));
        assertEquals(42L, loaded.getParams().get("latency_ms"));
        assertEquals("attempt-2", failed.getParams().get("attempt_id"));
        assertEquals(3, failed.getParams().get("error_code"));
        assertEquals(67L, failed.getParams().get("latency_ms"));
        assertEquals("ad_tier_result", tier.getName());
        assertEquals(1, tier.getParams().get("tier_index"));
        assertEquals("loaded", optionalTier.getParams().get("outcome"));
    }
}
