package io.github.jherobred.pane

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pane is always dark, so system bar icons are always light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Engine.refresh(this)
        val running = VmService.state.value.let { it is VmState.Running || it is VmState.Starting }
        setContent {
            PaneTheme {
                var screen by rememberSaveable { mutableStateOf(if (running) "machine" else "home") }
                BackHandler(screen != "home") { screen = "home" }
                AnimatedContent(
                    screen,
                    transitionSpec = {
                        (fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.96f)) togetherWith
                            (fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 1.02f))
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        "setup" -> SetupScreen(onBack = { screen = "home" }, onStarted = { screen = "machine" })
                        "machine" -> MachineScreen(onHome = { screen = "home" })
                        else -> HomeScreen(onSetup = { screen = "setup" }, onOpen = { screen = "machine" })
                    }
                }
            }
        }
    }
}
