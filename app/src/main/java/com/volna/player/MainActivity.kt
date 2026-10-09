package com.volna.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.volna.player.ui.VolnaRoot
import com.volna.player.ui.theme.VolnaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VolnaTheme {
                VolnaRoot()
            }
        }
    }
}
