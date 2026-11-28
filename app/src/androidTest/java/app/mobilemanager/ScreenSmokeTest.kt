package app.mobilemanager

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mobilemanager.data.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap 4.2: Smoke-Tests der echten Bildschirme mit dem echten Hilt-Graphen –
 * Berechtigungen, Alarm-Versand (inkl. HTTPS-Pflicht für ntfy) und Launcher starten ohne Absturz.
 */
@RunWith(AndroidJUnit4::class)
class ScreenSmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun skipOnboarding() {
        runBlocking { SettingsRepository(context).setOnboardingDone() }
    }

    @Test
    fun permissionsScreenShowsStatusRows() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription("Berechtigungen").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Shizuku", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Shizuku", substring = true).onFirst().assertExists()
        }
    }

    @Test
    fun alertSettingsRejectPlainHttpNtfyServer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // „Wächter“ steht auch als Überschrift auf dem Dashboard – gezielt den Navigations-Tab nehmen.
            compose.onNode(hasText("Wächter") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            compose.onNodeWithText("Alarm-Versand").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Kanal").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ntfy").performScrollTo().performClick()
            // Server-Feld ist mit dem Standard https://ntfy.sh vorbelegt.
            compose.onNode(hasSetTextAction() and hasText("https://ntfy.sh")).performScrollTo().performTextReplacement("http://ntfy.example.org")
            compose.onNodeWithText("Nur gültige HTTPS-Adressen", substring = true).assertExists()
            compose.onNodeWithText("Speichern").performScrollTo().assertIsNotEnabled()
        }
    }

    @Test
    fun launcherStarts() {
        // Der Launcher ist standardmäßig deaktiviert (wird erst in den Einstellungen eingeschaltet).
        val component = ComponentName(context, "app.mobilemanager.launcher.ui.LauncherActivity")
        context.packageManager.setComponentEnabledSetting(
            component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
        try {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<android.app.Activity>(intent).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Suchen").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            context.packageManager.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
            )
        }
    }
}
