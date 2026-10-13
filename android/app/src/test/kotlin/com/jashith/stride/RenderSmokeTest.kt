package com.jashith.stride

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.jashith.stride.ui.theme.StrideTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composeRendersOnTheJvm() {
        compose.setContent { StrideTheme { Text("Stride") } }
        compose.onNodeWithText("Stride").assertIsDisplayed()
    }
}
