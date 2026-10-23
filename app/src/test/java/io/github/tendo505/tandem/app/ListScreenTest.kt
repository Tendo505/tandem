package io.github.tendo505.tandem.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import io.github.tendo505.tandem.app.ui.ListScreen
import io.github.tendo505.tandem.app.ui.ListViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val phone = Phone("ui", server = null)

    @After
    fun close() = phone.db.close()

    /** Room writes happen on its own threads; wait for them to reach the screen. */
    private fun waitForText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `adding and ticking items from the screen`() {
        val list = runBlocking { phone.repo.createList("Groceries") }
        val vm = ListViewModel(phone.repo, phone.engine, list)
        compose.setContent { TandemTheme { ListScreen(vm, back = {}) } }

        waitForText("Groceries")
        compose.onNodeWithText("This list is empty. Add the first item below.").assertIsDisplayed()
        compose.onNodeWithText("Sync is off. Lists stay on this phone until you add a server in Settings.", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("add-input").performTextInput("milk")
        compose.onNodeWithTag("add-input").performImeAction()
        waitForText("milk")

        compose.onNodeWithTag("check-milk").performClick()
        compose.waitUntil(5_000) { runBlocking { phone.repo.current(list).items.single().done } }
        val items = runBlocking { phone.repo.current(list).items }
        assertEquals("milk", items.single().text)
        assertTrue(items.single().done)
    }
}
