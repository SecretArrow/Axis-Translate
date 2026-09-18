package com.axis.translate.ui.navigation

/**
 * Single source of truth for navigation destinations.
 *
 * Bottom bar: Home, Camera, History, Favorites, Settings.
 * Secondary destinations are reachable from Home / Settings.
 */
object Routes {
    const val HOME = "home"
    const val CAMERA = "camera"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val GLOSSARY = "glossary"
    const val CONVERSATION = "conversation"
    const val DOCUMENTS = "documents"
    const val BATCH = "batch"
    const val MODEL = "model"

    /** Bottom navigation items in display order. */
    val BOTTOM_ITEMS = listOf(HOME, CAMERA, HISTORY, FAVORITES, SETTINGS)
}
