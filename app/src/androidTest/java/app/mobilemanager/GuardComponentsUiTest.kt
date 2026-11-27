package app.mobilemanager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.mobilemanager.ui.guard.ChoiceChips
import app.mobilemanager.ui.guard.SwitchRow
import app.mobilemanager.ui.theme.MobileManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 4.0: Compose-UI-Tests für die Bausteine, aus denen die kritischen Schalter (VPN, Sperren) bestehen. */
@RunWith(AndroidJUnit4::class)
class GuardComponentsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchRowToggles() {
        compose.setContent {
            MobileManagerTheme {
                var checked by remember { mutableStateOf(false) }
                SwitchRow(title = "Tracker-Blocker", subtitle = "Lokales VPN", checked = checked, onCheckedChange = { checked = it })
            }
        }
        val toggle = compose.onNode(isToggleable())
        toggle.assertIsOff()
        toggle.performClick()
        toggle.assertIsOn()
    }

    @Test
    fun choiceChipsSelectOne() {
        compose.setContent {
            MobileManagerTheme {
                var selected by remember { mutableStateOf(1) }
                ChoiceChips(options = listOf(1 to "5 Min.", 2 to "15 Min."), selected = selected, onSelect = { selected = it })
            }
        }
        compose.onNodeWithText("15 Min.").performClick()
        compose.onNodeWithText("15 Min.").assertIsSelected()
    }
}
