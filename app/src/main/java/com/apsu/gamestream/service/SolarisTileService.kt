package com.apsu.gamestream.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.apsu.gamestream.MainActivity
import com.apsu.gamestream.R
import com.apsu.gamestream.model.ServerState

class SolarisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (ProjectionStreamService.currentState) {
            ServerState.WAITING_FOR_PROJECTION,
            ServerState.READY,
            ServerState.STREAMING,
            -> {
                startService(ProjectionStreamService.stopIntent(this))
                updateTile()
            }
            ServerState.IDLE,
            ServerState.ERROR,
            -> openSolarisToStartServer()
        }
    }

    private fun openSolarisToStartServer() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_START_FROM_TILE)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                START_FROM_TILE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ProjectionStreamService.currentState
        tile.label = getString(R.string.app_name)
        tile.state = if (state.isActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                ServerState.IDLE -> "Server off"
                ServerState.WAITING_FOR_PROJECTION -> "Starting"
                ServerState.READY -> "Ready"
                ServerState.STREAMING -> "Streaming"
                ServerState.ERROR -> "Error"
            }
        }
        tile.updateTile()
    }

    private fun ServerState.isActive(): Boolean =
        this == ServerState.WAITING_FOR_PROJECTION ||
            this == ServerState.READY ||
            this == ServerState.STREAMING

    companion object {
        const val ACTION_START_FROM_TILE = "com.apsu.gamestream.START_FROM_TILE"
        private const val START_FROM_TILE_REQUEST_CODE = 1001

        fun requestTileRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, SolarisTileService::class.java),
                )
            }
        }
    }
}
