package com.jashith.stride

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jashith.stride.ui.AppRoot
import com.jashith.stride.ui.theme.StrideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as StrideApplication).graph
        setContent { StrideTheme { AppRoot(graph) } }
    }
}
