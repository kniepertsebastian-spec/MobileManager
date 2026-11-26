package app.mobilemanager.ui.cleaner

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.media.ExpiredFilesRepository
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
    val isExpiredMode: Boolean = false,
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
 *
 * Zwei Modi: alte Fotos ohne Personen (Phase 1) und alte Screenshots/Downloads (Phase 2.3).
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val repository: StorageCleanerRepository,
    private val expiredRepository: ExpiredFilesRepository,
    private val settings: SettingsRepository,
    private val guardSettings: GuardSettingsRepository,
) : AndroidViewModel(application) {

    private val expiredMode = savedStateHandle.get<String>("mode") == "expired"
    private val _state = MutableStateFlow(ReviewUiState(isExpiredMode = expiredMode))
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()
    private val history = ArrayDeque<Pair<MediaImage, Decision>>()

    init {
        viewModelScope.launch {
            val candidates = if (expiredMode) {
                expiredRepository.findExpired(guardSettings.current().expiredFilesAgeDays)
            } else {
                repository.reviewCandidates(settings.current().photoAgeMonths)
            }
            _state.value = ReviewUiState(isExpiredMode = expiredMode, loading = false, queue = candidates)
        }
    }

    fun kindLabel(file: MediaImage): String? = if (expiredMode) expiredRepository.kindOf(file).label else null

    fun swipeLeft() = decide(Decision.DELETE)
    fun swipeRight() = decide(Decision.KEEP)

    private fun decide(decision: Decision) {
        val image = _state.value.current ?: return
        history.addLast(image to decision)
        if (decision == Decision.KEEP) {
            viewModelScope.launch {
                if (expiredMode) expiredRepository.markKeep(image.id) else repository.markKeep(listOf(image.id))
            }
        }
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
        if (decision == Decision.KEEP) {
            viewModelScope.launch {
                if (expiredMode) expiredRepository.undoKeep(image.id) else repository.undoKeep(listOf(image.id))
            }
        }
        _state.update {
            it.copy(
                index = (it.index - 1).coerceAtLeast(0),
                toDelete = it.toDelete - image,
                keptCount = if (decision == Decision.KEEP) it.keptCount - 1 else it.keptCount,
            )
        }
    }

    /**
     * Ein einziger Android-Löschdialog für alle nach links gewischten Bilder/Videos.
     * `null`, wenn nur Nicht-Medien-Dateien (PDFs …) gelöscht werden – dann [deleteWithoutDialog].
     */
    fun buildDeleteRequest(): IntentSender? {
        val media = _state.value.toDelete.filter(::isMedia)
        if (media.isEmpty()) return null
        return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, media.map { it.uri })
            .intentSender
    }

    /** Nur Nicht-Medien-Dateien ausgewählt (setzt "Zugriff auf alle Dateien" voraus). */
    fun deleteWithoutDialog() = onDeleteResult(confirmed = true)

    fun onDeleteResult(confirmed: Boolean) {
        if (!confirmed) return
        val selected = _state.value.toDelete
        viewModelScope.launch {
            if (expiredMode) {
                val others = expiredRepository.deleteDirectly(selected.filterNot(::isMedia))
                expiredRepository.onDeleted(selected.filter(::isMedia) + others)
                guardSettings.setExpiredFilesCount((_state.value.remaining).coerceAtLeast(0))
            } else {
                repository.onImagesDeleted(selected)
            }
            history.clear()
            _state.update { it.copy(toDelete = emptyList(), finished = true) }
        }
    }

    private fun isMedia(file: MediaImage): Boolean =
        file.mimeType == null || file.mimeType.startsWith("image/") || file.mimeType.startsWith("video/")
}
