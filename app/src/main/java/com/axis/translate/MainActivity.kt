package com.axis.translate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.History as HistoryOutlined
import androidx.compose.material.icons.outlined.Home as HomeOutlined
import androidx.compose.material.icons.outlined.PhotoCamera as PhotoCameraOutlined
import androidx.compose.material.icons.outlined.Settings as SettingsOutlined
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.axis.translate.data.settings.ThemeMode
import com.axis.translate.ui.navigation.AppNavigator
import com.axis.translate.ui.navigation.AxisNavHost
import com.axis.translate.ui.navigation.PendingInput
import com.axis.translate.ui.navigation.Routes
import com.axis.translate.ui.theme.AxisTheme
import com.axis.translate.util.rememberContainer

/** Single-activity shell: theme collection, share-target intake, nav scaffold. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSharedIntent(intent)
        setContent {
            val container = rememberContainer()
            val lifecycleOwner = LocalLifecycleOwner.current
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }

            LaunchedEffect(container, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    container.settingsRepository.settings.collect { settings ->
                        themeMode = settings.themeMode
                    }
                }
            }

            AxisTheme(themeMode = themeMode) {
                AxisAppScaffold()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    /** Routes ACTION_SEND payloads into [PendingInput] for the target screens. */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        when {
            intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    PendingInput.sharedText.value = text
                }
            }
            intent.type?.startsWith("image/") == true -> {
                extractStreamUri(intent)?.let { PendingInput.sharedImageUri.value = it }
            }
            else -> {
                // text/html, text/markdown, application/octet-stream
                extractStreamUri(intent)?.let { PendingInput.sharedDocumentUri.value = it }
            }
        }
    }

    /** Type-safe EXTRA_STREAM extraction with the API 33+ typed overload. */
    private fun extractStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            }.getOrNull()
        } else {
            @Suppress("DEPRECATION")
            runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        }
}

/** App root: bottom bar + navigation graph. */
@Composable
private fun AxisAppScaffold() {
    val navController = rememberNavController()

    DisposableEffect(navController) {
        AppNavigator.controller = navController
        onDispose {
            if (AppNavigator.controller === navController) {
                AppNavigator.controller = null
            }
        }
    }

    Scaffold(
        bottomBar = { AxisBottomBar(navController) },
    ) { innerPadding ->
        AxisNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.HomeOutlined),
    BottomDestination(Routes.CAMERA, "Camera", Icons.Filled.PhotoCamera, Icons.Outlined.PhotoCameraOutlined),
    BottomDestination(Routes.HISTORY, "History", Icons.Filled.History, Icons.Outlined.HistoryOutlined),
    BottomDestination(Routes.FAVORITES, "Favorites", Icons.Filled.Star, Icons.Outlined.StarBorder),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.SettingsOutlined),
)

@Composable
private fun AxisBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        bottomDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (currentRoute != destination.route) {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}
