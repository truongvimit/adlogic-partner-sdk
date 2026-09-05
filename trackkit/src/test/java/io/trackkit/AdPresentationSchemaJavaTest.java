package io.trackkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Java source consumers keep the old constructors and can supply captured load correlation. */
public class AdPresentationSchemaJavaTest {
    @Test
    public void originalJavaConstructorsKeepTheirPayloads() {
        TrackkitEvents.Ad.Show shown = new TrackkitEvents.Ad.Show("home", AdFormat.INTERSTITIAL, "unit");
        TrackkitEvents.Ad.ShowFailed failed = new TrackkitEvents.Ad.ShowFailed("home", AdFormat.INTERSTITIAL, "unit", 3);
        TrackkitEvents.Ad.Closed closed = new TrackkitEvents.Ad.Closed("home", AdFormat.INTERSTITIAL, "unit");
        assertEquals("ad_show", shown.getName());
        assertEquals(3, failed.getParams().get("error_code"));
        assertEquals("ad_closed", closed.getName());
        assertNull(shown.getParams().get("attempt_id"));
        assertNull(failed.getParams().get("attempt_id"));
        assertNull(closed.getParams().get("attempt_id"));
    }

    @Test
    public void javaConstructorsCanCorrelateEachPresentationEvent() {
        TrackkitEvents.Ad.Show shown = new TrackkitEvents.Ad.Show("app_resume", AdFormat.APP_OPEN, "unit", "load-a");
        TrackkitEvents.Ad.ShowFailed failed = new TrackkitEvents.Ad.ShowFailed("app_resume", AdFormat.APP_OPEN, "unit", 5, "load-a");
        TrackkitEvents.Ad.Closed closed = new TrackkitEvents.Ad.Closed("app_resume", AdFormat.APP_OPEN, "unit", "load-a");
        assertEquals("load-a", shown.getParams().get("attempt_id"));
        assertEquals("load-a", failed.getParams().get("attempt_id"));
        assertEquals(5, failed.getParams().get("error_code"));
        assertEquals("load-a", closed.getParams().get("attempt_id"));
    }
}
