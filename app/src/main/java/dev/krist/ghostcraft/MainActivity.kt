package dev.krist.ghostcraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    // Re-read on every return to the app: a sync may have happened meanwhile
    private val instances = MutableStateFlow<List<Instance>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val palette = remember { Palette(emptyMap(), isLight = false, sekai = true) }
            val list by instances.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalPalette provides palette) {
                Box(Modifier.fillMaxSize().background(palette.surface)) {
                    App(list) { reload() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Thread { instances.value = Instance.all(this) }.start()
    }
}

private sealed interface Page {
    data object Home : Page
    data object Core : Page
    data object Create : Page
    data class Open(val id: String) : Page
}

@androidx.compose.runtime.Composable
private fun App(instances: List<Instance>, reload: () -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var core by rememberSaveable { mutableStateOf(false) }
    var create by rememberSaveable { mutableStateOf(false) }
    val page: Page = when {
        core -> Page.Core
        create -> Page.Create
        openId != null -> instances.find { it.id == openId }?.let { Page.Open(it.id) } ?: Page.Home
        else -> Page.Home
    }
    BackHandler(page != Page.Home) {
        if (core) core = false else if (create) create = false else openId = null
    }
    AnimatedContent(
        page,
        transitionSpec = {
            val forward = targetState != Page.Home
            (slideInHorizontally(ghostSpring()) { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally(ghostSpring()) { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "page",
    ) { target ->
        when (target) {
            Page.Home -> HomeScreen(
                instances, onOpen = { openId = it.id }, onRuntime = { core = true },
                onCreate = { create = true }, onReload = reload,
            )
            Page.Create -> CreateScreen { create = false }
            Page.Core -> SettingsScreen { core = false }
            is Page.Open -> instances.find { it.id == target.id }?.let { InstanceScreen(it) { openId = null } }
        }
    }
}
