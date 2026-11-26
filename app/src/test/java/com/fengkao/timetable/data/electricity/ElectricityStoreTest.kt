package com.fengkao.timetable.data.electricity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ElectricityStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesLoadsAndReplacesSnapshotAtomically() {
        val directory = temporaryFolder.newFolder()
        val store = ElectricityStore(directory)
        val first = snapshot("101", 10.0)
        val second = snapshot("202", 5.5)

        store.save(first)
        assertEquals(first, store.load())
        store.save(second)

        assertEquals(second, store.load())
        assertFalse(File(directory, "${ElectricityStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun corruptCacheReturnsNull() {
        val directory = temporaryFolder.newFolder()
        File(directory, ElectricityStore.FILE_NAME).writeText("{")

        assertNull(ElectricityStore(directory).load())
    }

    @Test
    fun clearRemovesCache() {
        val directory = temporaryFolder.newFolder()
        val store = ElectricityStore(directory)
        store.save(snapshot("303", 20.0))

        store.clear()

        assertFalse(File(directory, ElectricityStore.FILE_NAME).exists())
        assertNull(store.load())
        assertTrue(directory.exists())
    }

    private fun snapshot(room: String, remaining: Double) = ElectricitySnapshot(
        rooms = listOf(
            ElectricityRoom(
                roomName = room,
                usedKwh = 100.0,
                remainingKwh = remaining,
                isPoweredOn = true,
            ),
        ),
        fetchedAt = 123L,
    )
}
