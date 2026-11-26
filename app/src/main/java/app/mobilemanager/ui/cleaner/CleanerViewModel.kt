package app.mobilemanager.ui.cleaner

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.media.StorageCleanerRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.model.DuplicateGroup
import app.mobilemanager.domain.model.GrantState
import app.mobilemanager.domain.model.MediaImage
import app.mobilemanager.domain.model.ScanProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CleanerUiState(
    val mediaAccess: GrantState = GrantState.DENIED,
    val photoAgeMonths: Int = 6,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val duplicateGroups: List<DuplicateGroup>? = null,
    /** IDs der zum Löschen markierten Duplikate. Standard: alle außer dem geschützten Original. */
    val selectedDuplicateIds: Set<Long> = emptySet(),
    val withoutPeopleCount: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class CleanerViewModel @Inject constructor(
    application: Application,
    private val repository: StorageCleanerRepository,
    private val permissions: PermissionChecker,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CleanerUiState())
    val state: StateFlow<CleanerUiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var pendingDeletion: List<MediaImage> = emptyList()
    private var handledScanToken = 0L

    /** Startet einen vom Dashboard angeforderten Scan genau einmal pro Token. */
    fun onScanRequested(token: Long) {
        if (token == 0L || token == handledScanToken) return
        handledScanToken = token
        startScan()
    }

    fun refresh() {
        viewModelScope.launch {
            val months = settings.current().photoAgeMonths
            val access = permissions.mediaAccessState()
            _state.update { it.copy(mediaAccess = access, photoAgeMonths = months) }
            if (access != GrantState.DENIED && !_state.value.scanning) {
                val count = repository.reviewCandidates(months).size
                _state.update { it.copy(withoutPeopleCount = count) }
            }
        }
    }

    fun setPhotoAgeMonths(months: Int) {
        viewModelScope.launch {
            settings.setPhotoAgeMonths(months)
            refresh()
        }
    }

    /** Vollständiger Scan: Duplikate, danach ML-Kit-Analyse alter Bilder. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        if (permissions.mediaAccessState() == GrantState.DENIED) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null) }
            try {
                val groups = repository.findDuplicates { p -> _state.update { it.copy(progress = p) } }
                _state.update { state ->
                    state.copy(
                        duplicateGroups = groups,
                        selectedDuplicateIds = groups.flatMap { g -> g.duplicates.map { it.id } }.toSet(),
                    )
                }
                val months = settings.current().photoAgeMonths
                val count = repository.analyzeOldImages(months) { p -> _state.update { it.copy(progress = p) } }
                _state.update { it.copy(withoutPeopleCount = count) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Scan fehlgeschlagen") }
            } finally {
                _state.update { it.copy(scanning = false, progress = null) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun toggleDuplicate(group: DuplicateGroup, image: MediaImage) {
        _state.update { state ->
            val selected = state.selectedDuplicateIds.toMutableSet()
            if (image.id in selected) {
                selected -= image.id
            } else {
                selected += image.id
                // Mindestens eine Kopie pro Gruppe muss erhalten bleiben.
                if (group.all.all { it.id in selected }) selected -= image.id
            }
            state.copy(selectedDuplicateIds = selected)
        }
    }

    /** Erstellt den Android-Löschdialog (Scoped Storage) für die markierten Duplikate. */
    fun buildDuplicateDeleteRequest(): IntentSender? {
        val state = _state.value
        val images = state.duplicateGroups.orEmpty().flatMap { it.all }.filter { it.id in state.selectedDuplicateIds }
        return buildDeleteRequest(images)
    }

    private fun buildDeleteRequest(images: List<MediaImage>): IntentSender? {
        if (images.isEmpty()) return null
        pendingDeletion = images
        return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, images.map { it.uri })
            .intentSender
    }

    fun onDeleteResult(confirmed: Boolean) {
        val deleted = pendingDeletion
        pendingDeletion = emptyList()
        if (!confirmed || deleted.isEmpty()) return
        val deletedIds = deleted.map { it.id }.toSet()
        viewModelScope.launch {
            repository.onImagesDeleted(deleted)
            _state.update { state ->
                state.copy(
                    duplicateGroups = state.duplicateGroups?.mapNotNull { group ->
                        val remaining = group.all.filterNot { it.id in deletedIds }
                        if (remaining.size < 2) null else group.copy(keep = remaining.first(), duplicates = remaining.drop(1))
                    },
                    selectedDuplicateIds = state.selectedDuplicateIds - deletedIds,
                )
            }
        }
    }
}
