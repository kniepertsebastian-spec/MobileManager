package app.mobilemanager.viewmodel

import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.settings.AppSettings
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.model.InactiveAppsResult
import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.StopProgress
import app.mobilemanager.domain.usecase.GetInactiveAppsUseCase
import app.mobilemanager.domain.usecase.StopAppsUseCase
import app.mobilemanager.ui.appstopper.AppStopperViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AppStopperViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val spotify = app("com.spotify.music", "Spotify")
    private val maps = app("com.google.android.apps.maps", "Maps")

    private val getInactiveApps = mockk<GetInactiveAppsUseCase> {
        coEvery { this@mockk.invoke() } returns InactiveAppsResult(48, listOf(spotify, maps), listOf(spotify))
    }
    private val stopApps = mockk<StopAppsUseCase> {
        every { availableMethod(any()) } returns ForceStopMethod.SHIZUKU
    }
    private val appsRepository = mockk<InstalledAppsRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { this@mockk.settings } returns flowOf(AppSettings())
        coEvery { current() } returns AppSettings(showSystemApps = true)
    }
    private val permissions = mockk<PermissionChecker> { every { hasUsageAccess() } returns true }
    private val shizukuState = MutableStateFlow(ShizukuState.READY)
    private val shizuku = mockk<ShizukuManager> { every { state } returns shizukuState }

    private fun viewModel() = AppStopperViewModel(getInactiveApps, stopApps, appsRepository, settings, permissions, shizuku)

    @Test
    fun reloadPublishesInactiveApps() = runTest {
        val vm = viewModel()
        vm.reload()
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(48, state.thresholdHours)
        assertEquals(listOf(spotify), state.candidates)
        assertEquals(true, state.showSystemApps)
        assertEquals(ForceStopMethod.SHIZUKU, state.method)
    }

    @Test
    fun shizukuStateIsMirrored() = runTest {
        val vm = viewModel()
        every { stopApps.availableMethod(any()) } returns null
        shizukuState.value = ShizukuState.NOT_RUNNING
        assertEquals(ShizukuState.NOT_RUNNING, vm.state.value.shizukuState)
        assertNull(vm.state.value.method)
    }

    @Test
    fun confirmStopRunsUseCaseForCandidatesAndReloads() = runTest {
        val done = StopProgress(current = 0, total = 1, currentLabel = "Spotify", succeeded = 1, failed = 0, finished = true)
        every { stopApps.invoke(listOf(spotify), ForceStopMethod.SHIZUKU) } returns flowOf(done)
        val vm = viewModel()
        vm.reload()
        vm.requestCleanup()
        assertEquals(listOf(spotify), vm.state.value.confirmStop)
        vm.confirmStop()
        assertNull(vm.state.value.confirmStop)
        assertEquals(done, vm.state.value.progress)
        verify(exactly = 1) { stopApps.invoke(listOf(spotify), ForceStopMethod.SHIZUKU) }
        coVerify(atLeast = 2) { getInactiveApps.invoke() }
    }

    @Test
    fun confirmStopWithoutMethodDoesNothing() = runTest {
        every { stopApps.availableMethod(any()) } returns null
        val vm = viewModel()
        vm.requestStopSingle(spotify)
        vm.confirmStop()
        verify(exactly = 0) { stopApps.invoke(any(), any()) }
        assertNull(vm.state.value.confirmStop)
    }

    @Test
    fun whitelistingPersistsAndReloads() = runTest {
        val vm = viewModel()
        vm.setWhitelisted(spotify, true)
        coVerify { appsRepository.setWhitelisted("com.spotify.music", true) }
        coVerify(atLeast = 1) { getInactiveApps.invoke() }
    }

    private fun app(pkg: String, label: String) = InstalledApp(
        packageName = pkg, label = label, isSystemApp = false, lastUsed = null,
        isStopped = false, autoProtection = null, isWhitelisted = false,
    )
}
