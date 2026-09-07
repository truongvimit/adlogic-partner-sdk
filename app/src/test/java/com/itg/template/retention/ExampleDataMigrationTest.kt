package com.itg.template.retention

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Historical strings are migration input, never new business operations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExampleDataMigrationTest {
    private lateinit var context: Context
    @Before fun oldInstallation() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE).edit().clear()
            .putString("state", """{
                "input":"An unfinished personal note", "last_feature":"translate", "last_result":"Keep this result",
                "saved":["hello","thanks"],
                "operations":{
                    "old-pending":{"feature":"translate","result":"Keep this result","reported":false},
                    "old-reported":{"feature":"saved_phrases","result":"saved:hello","reported":true}
                }
            }""").commit()
    }
    @Test fun oldInstallationKeepsTextAndSuccessIdentityWhileOpeningTheGenericDestination() {
        val migrated = ExampleDataStore(context)
        assertEquals("notes", migrated.lastFeature())
        assertEquals("An unfinished personal note", migrated.input())
        assertEquals("Keep this result", migrated.lastResult())
        val pending = migrated.pendingSuccesses().single()
        assertEquals("old-pending", pending.id)
        assertEquals("translate", pending.featureId) // Keep the original durable event identity.
        assertEquals("Keep this result", pending.result)
        migrated.markReported(pending.id)
        assertTrue(ExampleDataStore(context).pendingSuccesses().isEmpty())
        assertEquals("notes", ExampleDataStore(context).lastFeature())
    }
}
