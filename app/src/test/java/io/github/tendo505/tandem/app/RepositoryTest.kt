package io.github.tendo505.tandem.app

import android.app.Application
import io.github.tendo505.tandem.app.data.TandemRepository
import io.github.tendo505.tandem.core.Item
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
class RepositoryTest {
    private val phone = Phone("me", server = null)

    @After
    fun close() = phone.db.close()

    private suspend fun texts(list: String) = phone.repo.current(list).items.map { it.text }

    @Test
    fun `moving items reorders them`() = runBlocking {
        val list = phone.repo.createList("List")
        val a = phone.repo.addItem(list, "a")
        phone.repo.addItem(list, "b")
        val c = phone.repo.addItem(list, "c")
        phone.repo.move(list, c, -1)
        assertEquals(listOf("a", "c", "b"), texts(list))
        phone.repo.move(list, a, +2)
        assertEquals(listOf("c", "b", "a"), texts(list))
        phone.repo.move(list, a, +1) // already last
        assertEquals(listOf("c", "b", "a"), texts(list))
    }

    @Test
    fun `a key can always be found, even next to tied positions`() {
        // Two phones appended at the same moment and got the same key.
        val items = listOf(Item("item-a", "a", false, "V"), Item("item-b", "b", false, "V"), Item("item-c", "c", false, "W"))
        val key = TandemRepository.keyAt(items, 1)
        assertTrue(key > "V" && key < "W")
        val first = TandemRepository.keyAt(items, 0)
        assertTrue(first < "V")
    }

    @Test
    fun `deleting and restoring, and clearing completed items`() = runBlocking {
        val list = phone.repo.createList("List")
        val milk = phone.repo.addItem(list, "milk")
        val eggs = phone.repo.addItem(list, "eggs")
        phone.repo.delete(list, milk)
        assertEquals(listOf("eggs"), texts(list))
        phone.repo.restore(list, milk)
        assertEquals(listOf("milk", "eggs"), texts(list))
        phone.repo.setDone(list, eggs, true)
        phone.repo.clearDone(list)
        assertEquals(listOf("milk"), texts(list))
    }

    @Test
    fun `text is tidied and capped`() = runBlocking {
        val list = phone.repo.createList("  Big   shop  ")
        phone.repo.addItem(list, "x".repeat(500))
        assertEquals("Big shop", phone.repo.current(list).title)
        assertEquals(200, texts(list).single().length)
    }

    @Test
    fun `the overview shows progress and unsynced changes`() = runBlocking {
        val list = phone.repo.createList("Chores")
        val dishes = phone.repo.addItem(list, "dishes")
        phone.repo.addItem(list, "laundry")
        phone.repo.setDone(list, dishes, true)
        val summary = phone.repo.lists.first().single()
        assertEquals("Chores", summary.title)
        assertEquals(2, summary.total)
        assertEquals(1, summary.done)
        assertEquals(6, summary.pending) // title + 2 x (position, text) + done
    }

    @Test
    fun `leaving forgets the list and its queued changes`() = runBlocking {
        val list = phone.repo.createList("Temp")
        phone.repo.addItem(list, "x")
        phone.repo.leave(list)
        assertTrue(phone.repo.lists.first().isEmpty())
        assertEquals(0, phone.repo.pending(list).first())
    }

    @Test
    fun `list codes are found in a pasted share message`() {
        assertEquals("list-abc123def456", TandemRepository.parseCode("Join my Tandem list \"Trip\" with this code: list-abc123def456"))
        assertEquals("list-abc123def456", TandemRepository.parseCode("  list-abc123def456 "))
        assertNull(TandemRepository.parseCode("hello"))
        assertNull(TandemRepository.parseCode("list-"))
    }
}
