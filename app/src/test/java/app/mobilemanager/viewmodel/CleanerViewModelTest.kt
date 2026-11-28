package app.mobilemanager.viewmodel

import android.app.Application
import android.net.Uri
import app.mobilemanager.data.media.StorageCleanerRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.settings.AppSettings
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.model.DuplicateGroup
import app.mobilemanager.domain.model.GrantState
import app.mobilemanager.domain.model.MediaImage
import app.mobilemanager.ui.cleaner.CleanerViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CleanerViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val a = image(1)
    private val b = image(2)
    private val c = image(3)
    private val group = DuplicateGroup("hash", keep = a, duplicates = listOf(b, c))

    private val repository = mockk<StorageCleanerRepository>(relaxed = true) {
        coEvery { findDuplicates(any()) } returns listOf(group)
        coEvery { analyzeOldImages(any(), any()) } returns 7
    }
    private val permissions = mockk<PermissionChecker> { every { mediaAccessState() } returns GrantState.GRANTED }
    private val settings = mockk<SettingsRepository>(relaxed = true) { coEvery { current() } returns AppSettings() }

    private fun viewModel() = CleanerViewModel(mockk<Application>(relaxed = true), repository, permissions, settings)

    @Test
    fun scanSelectsAllDuplicatesButKeepsOriginal() = runTest {
        val vm = viewModel()
        vm.startScan()
        val state = vm.state.value
        assertFalse(state.scanning)
        assertEquals(setOf(2L, 3L), state.selectedDuplicateIds)
        assertEquals(7, state.withoutPeopleCount)
        assertNull(state.progress)
    }

    @Test
    fun atLeastOneCopyPerGroupStaysUnselected() = runTest {
        val vm = viewModel()
        vm.startScan()
        vm.toggleDuplicate(group, a)
        // Original zusätzlich markieren würde die ganze Gruppe löschen → wird verweigert.
        assertEquals(setOf(2L, 3L), vm.state.value.selectedDuplicateIds)
        vm.toggleDuplicate(group, b)
        vm.toggleDuplicate(group, a)
        assertEquals(setOf(1L, 3L), vm.state.value.selectedDuplicateIds)
    }

    @Test
    fun scanErrorIsShownAndScanningEnds() = runTest {
        coEvery { repository.findDuplicates(any()) } throws IllegalStateException("MediaStore weg")
        val vm = viewModel()
        vm.startScan()
        assertEquals("MediaStore weg", vm.state.value.error)
        assertFalse(vm.state.value.scanning)
    }

    @Test
    fun noScanWithoutMediaAccess() = runTest {
        every { permissions.mediaAccessState() } returns GrantState.DENIED
        val vm = viewModel()
        vm.startScan()
        coVerify(exactly = 0) { repository.findDuplicates(any()) }
    }

    @Test
    fun dashboardScanTokenStartsScanOnlyOnce() = runTest {
        val vm = viewModel()
        vm.onScanRequested(5L)
        vm.onScanRequested(5L)
        vm.onScanRequested(0L)
        coVerify(exactly = 1) { repository.findDuplicates(any()) }
        assertTrue(vm.state.value.duplicateGroups!!.isNotEmpty())
    }

    private fun image(id: Long) = MediaImage(
        id = id, uri = mockk<Uri>(), displayName = "IMG_$id.jpg", sizeBytes = 1_000, dateTaken = 0,
        dateModified = 0, bucket = "Camera", relativePath = "DCIM/Camera/",
    )
}
