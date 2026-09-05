package io.trackkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AdBoundSchemaJavaTest {
    @Test
    public void javaCanReportABoundCreativeWithOrWithoutItsUnit() {
        TrackkitEvents.Ad.Bound known =
                new TrackkitEvents.Ad.Bound("language", AdFormat.NATIVE, "native-unit");
        TrackkitEvents.Ad.Bound unknown =
                new TrackkitEvents.Ad.Bound("onboarding", AdFormat.NATIVE_FULL_SCREEN);

        assertEquals("ad_bound", known.getName());
        assertEquals("language", known.getParams().get("placement"));
        assertEquals("native", known.getParams().get("ad_format"));
        assertEquals("native-unit", known.getParams().get("ad_unit_id"));
        assertEquals(3, known.getParams().size());
        assertEquals("ad_bound", unknown.getName());
        assertEquals("onboarding", unknown.getParams().get("placement"));
        assertEquals("native_full_screen", unknown.getParams().get("ad_format"));
        assertNull(unknown.getParams().get("ad_unit_id"));
    }
}
