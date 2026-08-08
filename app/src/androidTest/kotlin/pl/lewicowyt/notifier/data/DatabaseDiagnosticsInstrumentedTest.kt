package pl.lewicowyt.notifier.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseDiagnosticsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun bundledDatabaseSnapshotAndQuickCheckAreHealthy() {
        context.deleteDatabase(DATABASE_NAME)
        val database = LocalDatabase(context, DATABASE_NAME)
        try {
            val state = database.diagnosticState()
            assertEquals("BUNDLED_SQLITE", state.engine)
            assertEquals(24, state.userVersion)
            assertEquals(24, state.appSchemaVersion)
            assertTrue(state.sqliteVersion.isNotBlank())
            assertTrue(state.journalMode.isNotBlank())
            assertTrue(state.fts5Available)
            assertEquals("ok", database.quickCheck().lowercase())
        } finally {
            database.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "database-diagnostics-test.db"
    }
}
