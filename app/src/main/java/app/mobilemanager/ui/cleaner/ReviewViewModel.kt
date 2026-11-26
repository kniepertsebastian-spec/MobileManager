package app.mobilemanager.ui.cleaner

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.media.StorageCleanerRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.model.MediaImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private enum class Decision { DELETE, KEEP }

data class ReviewUiState(
    val loading: Boolean = true,
    val queue: List<MediaImage> = emptyList(),
    val index: Int = 0,
    val toDelete: List<MediaImage> = emptyList(),
    val keptCount: Int = 0,
    val finished: Boolean = false,
) {
    val current: MediaImage? get() = queue.getOrNull(index)
    val next: MediaImage? get() = queue.getOrNull(index + 1)
    val remaining: Int get() = (queue.size - index).coerceAtLeast(0)
    val canUndo: Boolean get() = index > 0
}

/**
 * Swipe-Review: links = auf die Löschliste, rechts = behalten (in Room als "Ignorieren" markiert).
 * Gelöscht wird am Ende gesammelt über einen einzigen Systemdialog.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    application: Application,
    private val repository: StorageCleanerRepository,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()
    private val history = ArrayDeque<Pair<MediaImage, Decision>>()

    init {
        viewModelScope.launch {
            val candidates = repository.reviewCandidates(settings.current().photoAgeMonths)
            _state.value = ReviewUiState(loading = false, queue = candidates)
        }
    }

    fun swipeLeft() = decide(Decision.DELETE)
    fun swipeRight() = decide(Decision.KEEP)

    private fun decide(decision: Decision) {
        val image = _state.value.current ?: return
        history.addLast(image to decision)
        if (decision == Decision.KEEP) viewModelScope.launch { repository.markKeep(listOf(image.id)) }
        _state.update {
            it.copy(
                index = it.index + 1,
                toDelete = if (decision == Decision.DELETE) it.toDelete + image else it.toDelete,
                keptCount = if (decision == Decision.KEEP) it.keptCount + 1 else it.keptCount,
            )
        }
    }

    fun undo() {
        val (image, decision) = history.removeLastOrNull() ?: return
        if (decision == Decision.KEEP) viewModelScope.launch { repository.undoKeep(listOf(image.id)) }
        _state.update {
            it.copy(
                index = (it.index - 1).coerceAtLeast(0),
                toDelete = it.toDelete - image,
                keptCount = if (decision == Decision.KEEP) it.keptCount - 1 else it.keptCount,
            )
        }
    }

    /** Ein einziger Android-Löschdialog für alle nach links gewischten Bilder. */
    fun buildDeleteRequest(): IntentSender? {
        val images = _state.value.toDelete
        if (images.isEmpty()) return null
        return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, images.map { it.uri })
            .intentSender
    }

    fun onDeleteResult(confirmed: Boolean) {
        if (!confirmed) return
        val deleted = _state.value.toDelete
        viewModelScope.launch {
            repository.onImagesDeleted(deleted)
            history.clear()
            _state.update { it.copy(toDelete = emptyList(), finished = true) }
        }
    }
}
