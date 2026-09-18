package com.axis.translate

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.unit.dp
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

/** Navigation-bar scrims for the edge-to-edge system bar styling. */
private val LightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/** Window width from which the adaptive navigation rail is used (M3 expanded). */
private val NavigationRailBreakpoint = 840.dp

/** Single-activity shell: theme collection, share-target intake, adaptive nav scaffold. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleSharedIntent(intent)
        setContent {
            val container = rememberContainer()
            val lifecycleOwner = LocalLifecycleOwner.current
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            var dynamicColor by remember { mutableStateOf(true) }

            LaunchedEffect(container, lifecycleOwner) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    container.settingsRepository.settings.collect { settings ->
                        themeMode = settings.themeMode
                        dynamicColor = settings.dynamicColor
                    }
                }
            }

            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Keep system bar icon contrast in sync with the resolved app theme,
            // covering manual Light/Dark overrides and not just the system setting.
            DisposableEffect(darkTheme) {
                this@MainActivity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        LightScrim,
                        DarkScrim
                    ) { darkTheme }
                )
                onDispose { }
            }

            AxisTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
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
                extractStreamUri(intent)?.let {
                    PendingInput.sharedImageUri.value = it
                    // The photo screen consumes the payload when composed —
                    // route there so a share from another app lands on it.
                    AppNavigator.navigate(AppNavigator.PHOTO_ROUTE)
                }
            }
            else -> {
                // text/html, text/markdown, application/octet-stream
                extractStreamUri(intent)?.let {
                    PendingInput.sharedDocumentUri.value = it
                    // Same for the documents screen.
                    AppNavigator.navigate(Routes.DOCUMENTS)
                }
            }
        }
    }

    /** Type-safe EXTRA_STREAM extraction with the API 33+ typed overload. */
    private fun extractStreamUri(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        }.getOrNull()
    } else {
        @Suppress("DEPRECATION")
        runCatching { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
    }
}

/** App root: adaptive navigation (bottom bar or rail) + navigation graph. */
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

    BoxWithConstraints {
        if (maxWidth >= NavigationRailBreakpoint) {
            // Expanded windows: side rail navigation, content inset for system bars.
            Row(modifier = Modifier.fillMaxSize()) {
                AxisNavRail(navController)
                AxisNavHost(
                    navController = navController,
                    modifier = Modifier
                        .weight(1f)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                )
            }
        } else {
            Scaffold(
                bottomBar = { AxisBottomBar(navController) }
            ) { innerPadding ->
                AxisNavHost(
                    navController = navController,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.HomeOutlined),
    BottomDestination(Routes.CAMERA, "Camera", Icons.Filled.PhotoCamera, Icons.Outlined.PhotoCameraOutlined),
    BottomDestination(Routes.HISTORY, "History", Icons.Filled.History, Icons.Outlined.HistoryOutlined),
    BottomDestination(Routes.FAVORITES, "Favorites", Icons.Filled.Star, Icons.Outlined.StarBorder),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.SettingsOutlined)
)

/** Single top navigation with state save/restore, shared by bar and rail items. */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

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
                        navController.navigateSingleTop(destination.route)
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label) }
            )
        }
    }
}

@Composable
private fun AxisNavRail(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationRail {
        bottomDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationRailItem(
                selected = selected,
                onClick = {
                    if (currentRoute != destination.route) {
                        navController.navigateSingleTop(destination.route)
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label) }
            )
        }
    }
}
