package com.axis.translate.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.axis.translate.MainActivity

/**
 * Quick Settings tile: a one-tap shortcut that opens the main translator.
 * The tile flashes active briefly (visual feedback) and returns to its
 * inactive state shortly afterwards.
 */
class TranslateTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile
        tile?.state = Tile.STATE_ACTIVE
        tile?.updateTile()

        launchMainActivity()

        handler.postDelayed({ resetTile() }, TILE_RESET_DELAY_MS)
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun resetTile() {
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TILE_RESET_DELAY_MS = 500L
    }
}
