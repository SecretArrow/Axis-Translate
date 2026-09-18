package com.axis.translate.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.axis.translate.ui.batch.BatchScreen
import com.axis.translate.ui.camera.CameraScreen
import com.axis.translate.ui.conversation.ConversationScreen
import com.axis.translate.ui.document.DocumentsScreen
import com.axis.translate.ui.favorites.FavoritesScreen
import com.axis.translate.ui.glossary.GlossaryScreen
import com.axis.translate.ui.history.HistoryScreen
import com.axis.translate.ui.home.HomeScreen
import com.axis.translate.ui.model.ModelManagerScreen
import com.axis.translate.ui.photo.PhotoTranslateScreen
import com.axis.translate.ui.settings.SettingsScreen

/**
 * Root navigation graph. Bottom-bar destinations plus every secondary
 * destination in the app; all screens keep the fixed `(modifier)` signature.
 * Destination changes use a subtle Material fade-through motion.
 */
@Composable
fun AxisNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(220)) +
                scaleIn(animationSpec = tween(220), initialScale = 0.96f)
        },
        exitTransition = { fadeOut(animationSpec = tween(90)) },
        popEnterTransition = { fadeIn(animationSpec = tween(220)) },
        popExitTransition = { fadeOut(animationSpec = tween(90)) }
    ) {
        composable(Routes.HOME) { HomeScreen(Modifier) }
        composable(Routes.CAMERA) { CameraScreen(Modifier) }
        composable(AppNavigator.PHOTO_ROUTE) { PhotoTranslateScreen(Modifier) }
        // Defensive alias: any code calling AppNavigator.navigate("photo")
        // lands on the same screen without crashing on an unknown route.
        composable("photo") { PhotoTranslateScreen(Modifier) }
        composable(Routes.HISTORY) { HistoryScreen(Modifier) }
        composable(Routes.FAVORITES) { FavoritesScreen(Modifier) }
        composable(Routes.SETTINGS) { SettingsScreen(Modifier) }
        composable(Routes.GLOSSARY) { GlossaryScreen(Modifier) }
        composable(Routes.CONVERSATION) { ConversationScreen(Modifier) }
        composable(Routes.DOCUMENTS) { DocumentsScreen(Modifier) }
        composable(Routes.BATCH) { BatchScreen(Modifier) }
        composable(Routes.MODEL) { ModelManagerScreen(Modifier) }
    }
}
