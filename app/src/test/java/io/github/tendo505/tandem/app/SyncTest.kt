package io.github.tendo505.tandem.app

import android.app.Application
import io.github.tendo505.tandem.app.sync.SyncProblem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncTest {
    private val server = FakeServer()
    private val phones = mutableListOf<Phone>()

    private fun phone(name: String, wall: Long = 1_800_000_000_000, pageSize: Int = 500) =
        Phone(name, server, wall, pageSize).also { phones += it }

    @After
    fun close() = phones.forEach { it.db.close() }

    private fun Phone.texts(listId: String) = runBlocking { repo.current(listId).items.map { it.text } }

    @Test
    fun `a shared list reaches the other phone`() = runBlocking {
        val anna = phone("anna")
        val ben = phone("ben")
        val list = anna.repo.createList("Groceries")
        anna.repo.addItem(list, "milk")
        anna.repo.addItem(list, "eggs")
        assertNull(anna.engine.sync(listOf(list)))

        ben.repo.joinList(list)
        assertNull(ben.engine.syncAll())

        assertEquals(anna.repo.current(list), ben.repo.current(list))
        assertEquals("Groceries", ben.repo.current(list).title)
        assertEquals(listOf("milk", "eggs"), ben.texts(list))
    }

    /**
     * The scenario Tandem exists for: both phones go offline, both edit the
     * same list (including the same item), then reconnect in any order.
     */
    @Test
    fun `offline edits on two phones merge into the same list`() = runBlocking {
        val anna = phone("anna")
        val ben = phone("ben")
        val list = anna.repo.createList("Trip")
        val milk = anna.repo.addItem(list, "milk")
        val eggs = anna.repo.addItem(list, "eggs")
        anna.engine.sync(listOf(list))
        ben.repo.joinList(list)
        ben.engine.syncAll()

        server.online = false
        anna.later(); anna.repo.setText(list, milk, "oat milk")
        anna.later(); anna.repo.setDone(list, eggs, true)
        ben.later(5_000); ben.repo.setText(list, milk, "whole milk") // later than Anna's edit of the same item
        ben.later(); ben.repo.addItem(list, "bread")
        ben.later(); ben.repo.rename(list, "Weekend trip")
        assertEquals(SyncProblem.OFFLINE, anna.engine.syncAll())
        assertEquals(2, anna.repo.pending(list).first())

        server.online = true
        // Reconnect in the "wrong" order and sync until quiet.
        assertNull(ben.engine.syncAll())
        assertNull(anna.engine.syncAll())
        assertNull(ben.engine.syncAll())

        val a = anna.repo.current(list)
        assertEquals(a, ben.repo.current(list))
        assertEquals("Weekend trip", a.title)
        assertEquals(listOf("whole milk", "eggs", "bread"), a.items.map { it.text })
        assertTrue(a.items.single { it.id == eggs }.done)
        assertEquals(0, anna.repo.pending(list).first())
        assertEquals(0, ben.repo.pending(list).first())
    }

    @Test
    fun `an edit made after seeing a change wins even when this phone's clock is behind`() = runBlocking {
        val hour = 3_600_000L
        val fast = phone("fast", wall = 1_800_000_000_000 + hour) // clock an hour ahead
        val slow = phone("slow", wall = 1_800_000_000_000)
        val list = fast.repo.createList("List")
        val item = fast.repo.addItem(list, "from the fast phone")
        fast.engine.syncAll()

        slow.repo.joinList(list)
        slow.engine.syncAll()
        slow.later()
        slow.repo.setText(list, item, "corrected on the slow phone")
        slow.engine.syncAll()
        fast.engine.syncAll()

        assertEquals(listOf("corrected on the slow phone"), fast.texts(list))
        assertEquals(fast.repo.current(list), slow.repo.current(list))
    }

    @Test
    fun `long logs arrive in pages and the cursor never skips ops`() = runBlocking {
        val anna = phone("anna")
        val ben = phone("ben", pageSize = 3)
        val list = anna.repo.createList("Many")
        repeat(10) { anna.later(1); anna.repo.addItem(list, "item $it") }
        anna.engine.syncAll()

        ben.repo.joinList(list)
        ben.engine.syncAll()

        assertEquals((0 until 10).map { "item $it" }, ben.texts(list))
        assertEquals(server.opCount(list).toLong(), ben.db.dao().list(list)!!.cursor)
        val pullsBefore = server.pulls
        ben.engine.syncAll()
        assertEquals("an up-to-date phone needs one request", pullsBefore + 1, server.pulls)
    }

    @Test
    fun `re-sending after a lost response stores nothing twice`() = runBlocking {
        val anna = phone("anna")
        val list = anna.repo.createList("List")
        anna.repo.addItem(list, "milk")
        val outbox = anna.db.dao().outbox(list, 500).map { it.toOp() }
        // The push reached the server but the phone never heard back...
        server.push(list, io.github.tendo505.tandem.core.PushRequest(outbox))
        // ...so it sends the same ops again on the next sync.
        assertNull(anna.engine.syncAll())
        assertEquals(outbox.size, server.opCount(list))
    }

    @Test
    fun `refused changes stay queued and the reason is shown`() = runBlocking {
        val anna = phone("anna")
        val list = anna.repo.createList("List")
        server.refuse = "op timestamp is in the future; check the device clock"
        assertEquals(SyncProblem.REJECTED, anna.engine.syncAll())
        assertEquals("op timestamp is in the future; check the device clock", anna.engine.status.value.detail)
        assertEquals(1, anna.repo.pending(list).first())
    }

    @Test
    fun `without a server the app still works and says sync is off`() = runBlocking {
        val solo = Phone("solo", server = null).also { phones += it }
        val list = solo.repo.createList("Local only")
        solo.repo.addItem(list, "works offline")
        assertEquals(SyncProblem.NOT_CONFIGURED, solo.engine.syncAll())
        assertEquals(listOf("works offline"), solo.texts(list))
        assertTrue("every edit asks for a background sync", solo.syncRequests >= 3)
    }
}
