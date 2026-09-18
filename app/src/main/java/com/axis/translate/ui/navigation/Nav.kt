package com.axis.translate.ui.navigation

import androidx.navigation.NavHostController

/**
 * Static navigation bridge. Screens expose a fixed composable signature
 * (`XScreen(modifier: Modifier)`), so secondary destinations (model manager,
 * glossary, photo flow, ...) are reached through this app-wide handle instead
 * of threading a [NavHostController] through every composable.
 *
 * MainActivity installs the controller while the app scaffold is composed and
 * uninstalls it on dispose.
 */
object AppNavigator {

    /**
     * Photo (gallery image) translation flow. `Routes` has no dedicated photo
     * constant, so this destination is nested under the camera route — and a
     * plain `photo` alias is registered in the NavHost for safety.
     */
    const val PHOTO_ROUTE = "${Routes.CAMERA}/photo"

    @Volatile
    var controller: NavHostController? = null

    fun navigate(route: String) {
        controller?.navigate(route) {
            launchSingleTop = true
        }
    }

    fun navigateBack(): Boolean = controller?.popBackStack() ?: false
}
