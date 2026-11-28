package app.mobilemanager.viewmodel

import app.mobilemanager.data.alerts.AlertConfigStore
import app.mobilemanager.data.guard.GuardSettings
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.domain.model.AlertChannelType
import app.mobilemanager.domain.model.AlertConfig
import app.mobilemanager.ui.guard.AlertSettingsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.KeyStoreException

class AlertSettingsViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val store = mockk<AlertConfigStore>(relaxed = true)
    private val guardSettings = mockk<GuardSettingsRepository>(relaxed = true) {
        every { settings } returns flowOf(GuardSettings())
    }
    private val securityEvents = mockk<SecurityEventRepository>(relaxed = true)

    private fun viewModel() = AlertSettingsViewModel(store, guardSettings, securityEvents)

    @Test
    fun loadsStoredConfig() = runTest {
        coEvery { store.current() } returns AlertConfig(channel = AlertChannelType.TELEGRAM, telegramChatId = "42")
        val state = viewModel().state.value
        assertTrue(state.loaded)
        assertEquals("42", state.draft.telegramChatId)
        assertFalse(state.dirty)
    }

    @Test
    fun keystoreErrorOnLoadIsShownNotThrown() = runTest {
        coEvery { store.current() } throws KeyStoreException("Schlüssel ungültig")
        val state = viewModel().state.value
        assertTrue(state.loaded)
        assertTrue(state.messageIsError)
        assertTrue(state.message!!.contains("Schlüssel ungültig"))
    }

    @Test
    fun saveSuccessClearsDirtyFlag() = runTest {
        coEvery { store.current() } returns AlertConfig()
        val vm = viewModel()
        vm.edit { it.copy(channel = AlertChannelType.TELEGRAM, telegramToken = "t", telegramChatId = "1") }
        assertTrue(vm.state.value.dirty)
        vm.save()
        assertFalse(vm.state.value.dirty)
        assertFalse(vm.state.value.messageIsError)
        coVerify(exactly = 1) { store.save(match { it.telegramChatId == "1" }) }
    }

    @Test
    fun keystoreErrorOnSaveKeepsDraftAndReportsError() = runTest {
        coEvery { store.current() } returns AlertConfig()
        coEvery { store.save(any()) } throws KeyStoreException("kaputt")
        val vm = viewModel()
        vm.edit { it.copy(mailTo = "a@b.c") }
        vm.save()
        val state = vm.state.value
        assertTrue(state.dirty)
        assertTrue(state.messageIsError)
        assertEquals("a@b.c", state.draft.mailTo)
    }

    @Test
    fun plainHttpNtfyServerIsNeverSaved() = runTest {
        coEvery { store.current() } returns AlertConfig()
        val vm = viewModel()
        vm.edit { it.copy(channel = AlertChannelType.NTFY, ntfyServer = "http://ntfy.example.org", ntfyTopic = "x") }
        vm.save()
        vm.sendTest()
        coVerify(exactly = 0) { store.save(any()) }
        coVerify(exactly = 0) { securityEvents.sendTest() }
    }

    @Test
    fun testAlarmIsNotSentWhenSavingFails() = runTest {
        coEvery { store.current() } returns AlertConfig()
        coEvery { store.save(any()) } throws KeyStoreException("kaputt")
        val vm = viewModel()
        vm.edit { it.copy(channel = AlertChannelType.TELEGRAM, telegramToken = "t", telegramChatId = "1") }
        vm.sendTest()
        coVerify(exactly = 0) { securityEvents.sendTest() }
        assertFalse(vm.state.value.testing)
    }

    @Test
    fun testAlarmFailureIsReported() = runTest {
        coEvery { store.current() } returns AlertConfig()
        coEvery { securityEvents.sendTest() } returns Result.failure(IllegalStateException("HTTP 401"))
        val vm = viewModel()
        vm.edit { it.copy(channel = AlertChannelType.TELEGRAM, telegramToken = "t", telegramChatId = "1") }
        vm.sendTest()
        val state = vm.state.value
        assertFalse(state.testing)
        assertTrue(state.messageIsError)
        assertTrue(state.message!!.contains("HTTP 401"))
    }
}
