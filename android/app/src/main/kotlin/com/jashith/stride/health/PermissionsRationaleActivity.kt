package com.jashith.stride.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jashith.stride.ui.components.PrimaryButton
import com.jashith.stride.ui.theme.StrideColors
import com.jashith.stride.ui.theme.StrideTheme
import com.jashith.stride.ui.theme.StrideType

/** Health Connect shows this when someone taps "Learn more" on Stride's permission request. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrideTheme {
                Column(
                    Modifier.fillMaxSize().background(StrideColors.PageBackground).statusBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("How Stride uses Health Connect", style = StrideType.ScreenTitle)
                    Text(
                        "Stride reads heart rate, so your splits, zones and effort can use what your watch measured, and body weight, so calorie and power estimates start from the right number.",
                        style = StrideType.Body.copy(fontSize = 15.sp),
                    )
                    Text(
                        "It writes each finished run or ride as a workout, with its route, distance and calories, so it shows up in your other health apps.",
                        style = StrideType.Body.copy(fontSize = 15.sp),
                    )
                    Text(
                        "Nothing leaves your phone. Stride has no account and no server, and you can turn either direction off in You → General.",
                        style = StrideType.Body.copy(fontSize = 15.sp),
                    )
                    PrimaryButton("Close", onClick = { finish() })
                }
            }
        }
    }
}
