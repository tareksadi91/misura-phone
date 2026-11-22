package com.misura.phone.store

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageFailureHandlingTest {
    @Test fun keyLossAfterSuccessfulReadStopsOnlyFailedBackgroundJobAndPreservesRow() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, MisuraDb::class.java).build()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var available = true
        val cipher = StorageCipher({ if (available) key else throw KeyStoreException("fixture unavailable") }, {})
        val store = RoomTaskStore(db, cipher)
        val parent = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val scope = storageChildScope(parent)
        try {
            val task = Task("t", "message", TaskState.PLANNING, null, emptyList(), 0, null)
            store.save(task)
            val original = db.dao().task(task.id)
            val failed = scope.launch {
                val loaded = store.load(task.id)!!
                available = false
                store.save(loaded.copy(state = TaskState.FAILED))
                fail("unreadable existing row must reject replacement")
            }
            failed.join()
            assertTrue(failed.isCancelled)
            assertEquals(original, db.dao().task(task.id))
            assertEquals(42, scope.async { 42 }.await())
            assertEquals(43, parent.async { 43 }.await())
            available = true
            assertEquals(task, store.load(task.id))
        } finally {
            parent.cancel()
            assertFalse(scope.isActive)
            db.close()
        }
    }

    @Test fun unrelatedFailuresAreNotSwallowed() {
        val error = IllegalArgumentException("fixture programming error")
        try { storageFailureHandler.handleException(EmptyCoroutineContext, error); fail("must propagate") }
        catch (caught: IllegalArgumentException) { assertSame(error, caught) }
    }
}
