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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExampleDataStoreTest {
    private lateinit var context: Context
    @Before fun clearOwnFixture() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun resultAndUnreportedSuccessSurviveNewStoreInstance() {
        ExampleDataStore(context).record("operation-one", "text_tools", "Three words here")
        val restored = ExampleDataStore(context)
        assertEquals("Three words here", restored.lastResult())
        assertEquals("operation-one", restored.pendingSuccesses().single().id)
        restored.markReported("operation-one")
        assertTrue(ExampleDataStore(context).pendingSuccesses().isEmpty())
    }
    @Test fun replayKeepsOneStableSuccessUntilAcknowledged() {
        val store = ExampleDataStore(context)
        repeat(3) { store.record("stable-id", "notes", "A useful note") }
        assertEquals(1, store.pendingSuccesses().size)
        store.markReported("stable-id")
        store.record("stable-id", "notes", "A useful note")
        assertTrue(store.pendingSuccesses().isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun sameIdCannotRepresentDifferentResult() {
        val store = ExampleDataStore(context)
        store.record("id-one", "notes", "A")
        store.record("id-one", "notes", "B")
    }
    @Test fun savedMembershipAndSuccessCommitTogetherWithoutNoOpSuccess() {
        val store = ExampleDataStore(context)
        store.saveNote("note-one", "Remember the workshop", "create-one")
        assertNotNull(store.saveItem("note-one", "save-one"))
        assertNull(store.saveItem("note-one", "save-noop"))
        assertEquals(listOf("Remember the workshop"), ExampleDataStore(context).savedItems().map { it.text })
        store.saveNote("note-one", "The workshop is tomorrow", "edit-one")
        assertEquals("Remember the workshop", store.savedItems().single().text)
        assertEquals("The workshop is tomorrow", ExampleDataStore(context).notes().single().text)
        assertNotNull(store.removeItem("note-one", "remove-one"))
        assertNull(store.removeItem("note-one", "remove-noop"))
        assertTrue(ExampleDataStore(context).savedItems().isEmpty())
        assertEquals(setOf("create-one", "save-one", "edit-one", "remove-one"), store.pendingSuccesses().map { it.id }.toSet())
    }
    @Test fun unchangedNoteAndBlankInputCannotManufactureSuccess() {
        val store = ExampleDataStore(context)
        assertTrue(runCatching { store.saveNote("note-one", "  ", "blank") }.isFailure)
        assertNotNull(store.saveNote("note-one", "A real note", "created"))
        assertNull(store.saveNote("note-one", "A real note", "unchanged"))
        assertEquals(listOf("created"), store.pendingSuccesses().map { it.id })
    }
    @Test fun inputRestoresWithoutManufacturingBusinessSuccess() {
        ExampleDataStore(context).saveInput("unfinished text")
        val restored = ExampleDataStore(context)
        assertEquals("unfinished text", restored.input())
        assertTrue(restored.pendingSuccesses().isEmpty())
    }
    @Test fun failedAcknowledgementRestoresPendingAfterPreferencesMemoryWasAlreadyChanged() {
        var failNextCommit = false
        val real = context.getSharedPreferences("retention_example_data_v1", Context.MODE_PRIVATE)
        val wrapped = object : android.content.SharedPreferences by real {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = real.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor = apply { editor.putString(key, value) }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor = apply { editor.remove(key) }
                    override fun commit(): Boolean {
                        val actual = editor.commit()
                        return if (failNextCommit) { failNextCommit = false; false } else actual
                    }
                }
            }
        }
        val wrappedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = wrapped
        }
        val store = ExampleDataStore(wrappedContext)
        store.record("failed-ack", "notes", "A useful note")
        failNextCommit = true
        assertTrue(runCatching { store.markReported("failed-ack") }.isFailure)
        assertEquals("failed-ack", ExampleDataStore(context).pendingSuccesses().single().id)
        store.markReported("failed-ack")
        assertTrue(ExampleDataStore(context).pendingSuccesses().isEmpty())
    }

}
