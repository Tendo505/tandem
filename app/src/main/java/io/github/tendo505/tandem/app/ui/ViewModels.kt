package io.github.tendo505.tandem.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tendo505.tandem.app.data.ListSummary
import io.github.tendo505.tandem.app.data.TandemRepository
import io.github.tendo505.tandem.app.sync.SyncEngine
import io.github.tendo505.tandem.app.sync.SyncStatus
import io.github.tendo505.tandem.core.Item
import io.github.tendo505.tandem.core.ListView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ListsViewModel(private val repo: TandemRepository, private val sync: SyncEngine) : ViewModel() {
    val lists: StateFlow<List<ListSummary>?> = repo.lists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val status: StateFlow<SyncStatus> = sync.status

    fun refresh() = viewModelScope.launch { sync.syncAll() }

    fun create(title: String, then: (String) -> Unit) = viewModelScope.launch { then(repo.createList(title)) }

    /** Returns false if [text] contains no list code. */
    fun join(text: String, then: (String) -> Unit): Boolean {
        val id = TandemRepository.parseCode(text) ?: return false
        viewModelScope.launch {
            repo.joinList(id)
            then(id)
        }
        return true
    }
}

class ListViewModel(private val repo: TandemRepository, private val sync: SyncEngine, val listId: String) : ViewModel() {
    val list: StateFlow<ListView?> = repo.list(listId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val pending: StateFlow<Int> = repo.pending(listId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val status: StateFlow<SyncStatus> = sync.status

    /** While the list is on screen, poll so other people's edits show up within seconds. */
    suspend fun liveSync() {
        while (true) {
            val problem = sync.sync(listOf(listId))
            delay(if (problem == null) 3_000 else 10_000)
        }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repo.addItem(listId, text) }
    }

    fun setText(item: Item, text: String) {
        if (text.isBlank() || text.trim() == item.text) return
        viewModelScope.launch { repo.setText(listId, item.id, text) }
    }

    fun toggle(item: Item) = viewModelScope.launch { repo.setDone(listId, item.id, !item.done) }
    fun delete(item: Item) = viewModelScope.launch { repo.delete(listId, item.id) }
    fun restore(item: Item) = viewModelScope.launch { repo.restore(listId, item.id) }
    fun move(item: Item, by: Int) = viewModelScope.launch { repo.move(listId, item.id, by) }
    fun rename(title: String) = viewModelScope.launch { repo.rename(listId, title) }
    fun clearDone() = viewModelScope.launch { repo.clearDone(listId) }
    fun leave(then: () -> Unit) = viewModelScope.launch { repo.leave(listId); then() }
}
