package io.github.tendo505.tandem.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.tendo505.tandem.app.ui.ListScreen
import io.github.tendo505.tandem.app.ui.ListViewModel
import io.github.tendo505.tandem.app.ui.ListsScreen
import io.github.tendo505.tandem.app.ui.ListsViewModel
import io.github.tendo505.tandem.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TandemApp).container
        setContent { TandemTheme { TandemNav(container) } }
    }
}

@Composable
fun TandemNav(c: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "lists") {
        composable("lists") {
            val vm: ListsViewModel = viewModel(factory = viewModelFactory { initializer { ListsViewModel(c.repo, c.sync) } })
            ListsScreen(vm, open = { nav.navigate("list/$it") }, openSettings = { nav.navigate("settings") })
        }
        composable("list/{id}") { entry ->
            val id = requireNotNull(entry.arguments?.getString("id"))
            val vm: ListViewModel = viewModel(factory = viewModelFactory { initializer { ListViewModel(c.repo, c.sync, id) } })
            ListScreen(vm, back = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(c.settings, c.sync, back = { nav.popBackStack() })
        }
    }
}

@Composable
fun TandemTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E), primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCFBF1),
        secondaryContainer = Color(0xFF1F3B3A), surface = Color(0xFF131A1A), surfaceContainer = Color(0xFF1A2322), surfaceContainerLow = Color(0xFF161E1E),
        background = Color(0xFF0D1313), error = Color(0xFFF87171),
    ) else lightColorScheme(
        primary = Color(0xFF0F766E), onPrimary = Color.White, primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF042F2E),
        secondaryContainer = Color(0xFFDDF3EF), surface = Color.White, surfaceContainer = Color(0xFFEFF5F4), surfaceContainerLow = Color(0xFFF6FAF9),
        background = Color(0xFFF4F8F7), error = Color(0xFFC62828),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
