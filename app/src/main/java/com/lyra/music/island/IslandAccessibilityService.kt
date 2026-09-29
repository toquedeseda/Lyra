package com.lyra.music.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.lyra.music.LyraApp

/**
 * Solo sirve para que la isla pueda dibujarse encima de la barra de estado, junto
 * a la cámara. No lee la pantalla ni ningún evento de otras apps.
 */
class IslandAccessibilityService : AccessibilityService() {

    private val island get() = (application as LyraApp).container.island

    override fun onServiceConnected() {
        super.onServiceConnected()
        island.onAccessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        island.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        island.onAccessibilityDisconnected()
        super.onDestroy()
    }
}
