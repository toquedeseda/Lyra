package com.lyra.music.island

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lyra.music.data.settings.AppSettings
import kotlin.math.roundToInt

/** Posición de la isla en píxeles y tamaño de la píldora en dp. */
data class IslandGeometry(
    val x: Int,
    val y: Int,
    val widthDp: Int,
    val heightDp: Int,
) {
    companion object {
        /**
         * Centra la píldora en el agujero de la cámara si el móvil informa de él.
         * Si la isla no puede ir encima de la barra de estado, se coloca justo debajo.
         */
        fun from(context: Context, settings: AppSettings, overStatusBar: Boolean): IslandGeometry {
            val density = context.resources.displayMetrics.density
            val heightPx = settings.islandHeight * density
            var centerX = 0
            val top: Int
            if (overStatusBar) {
                val cutout = cutoutRect(context)
                top = if (cutout != null) {
                    centerX = cutout.centerX() - context.resources.displayMetrics.widthPixels / 2
                    (cutout.centerY() - heightPx / 2).roundToInt().coerceAtLeast(0)
                } else {
                    (6 * density).roundToInt()
                }
            } else {
                top = statusBarHeight(context) + (6 * density).roundToInt()
            }
            return IslandGeometry(
                x = centerX + (settings.islandOffsetX * density).roundToInt(),
                y = top + (settings.islandOffsetY * density).roundToInt(),
                widthDp = settings.islandWidth,
                heightDp = settings.islandHeight,
            )
        }

        private fun cutoutRect(context: Context): android.graphics.Rect? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val wm = context.getSystemService(WindowManager::class.java) ?: return null
            val cutout = wm.currentWindowMetrics.windowInsets.displayCutout ?: return null
            return cutout.boundingRects.firstOrNull { it.top < 200 && it.width() < context.resources.displayMetrics.widthPixels / 2 }
        }

        @SuppressLint("InternalInsetResource", "DiscouragedApi")
        private fun statusBarHeight(context: Context): Int {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * context.resources.displayMetrics.density).roundToInt()
        }
    }
}

/**
 * Ventana flotante con Compose dentro, alojada en un servicio.
 *
 * El tamaño de la ventana es fijo durante cada animación: antes de crecer se
 * agranda de una vez y, al terminar de encogerse, se reduce de una vez. Así la
 * animación ocurre entera dentro de Compose y no "salta" (con WRAP_CONTENT el
 * sistema recolocaba la ventana en cada fotograma).
 */
class IslandWindow(
    private val context: Context,
    private val type: Int,
    geometry: IslandGeometry,
    private val controller: IslandController,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val owner = WindowOwner()
    private var geometryState by mutableStateOf(geometry)
    private var attached = false

    private val params = WindowManager.LayoutParams(
        px(geometry.widthDp + IslandMetrics.MARGIN_H * 2),
        px(geometry.heightDp + IslandMetrics.MARGIN_BOTTOM),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = geometry.x
        y = geometry.y
        // Junto a la cámara (Android 9+; en Android 8 no existe y la app se cerraba).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Lyra isla"
        windowAnimations = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    private val view = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setOnTouchListener { _, event ->
            // Un toque fuera de la isla la recoge.
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (controller.shape.value != IslandShape.Pill) controller.collapse()
                true
            } else {
                false
            }
        }
        setContent { IslandContent(controller, geometryState, onWindowSize = ::resize) }
    }

    private fun px(dp: Int): Int = (dp * density).roundToInt()
    private fun px(dp: Float): Int = (dp * density).roundToInt()

    /** Tamaño de la ventana = tamaño de la isla + margen para el rebote del muelle. */
    private fun resize(size: DpSize) {
        val width = px(size.width.value + IslandMetrics.MARGIN_H * 2)
        val height = px(size.height.value + IslandMetrics.MARGIN_BOTTOM)
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        if (attached) runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun show() {
        if (attached) return
        owner.start()
        runCatching {
            windowManager.addView(view, params)
            attached = true
        }
    }

    fun update(geometry: IslandGeometry) {
        geometryState = geometry
        if (params.x != geometry.x || params.y != geometry.y) {
            params.x = geometry.x
            params.y = geometry.y
            if (attached) runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    fun remove() {
        if (attached) runCatching { windowManager.removeViewImmediate(view) }
        attached = false
        owner.stop()
    }

    val isAccessibilityHosted: Boolean get() = context is AccessibilityService

    private class WindowOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null)
        }

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}

/** Medidas de la isla (dp). */
object IslandMetrics {
    const val MARGIN_H = 10f
    const val MARGIN_BOTTOM = 14f
    const val NOTICE_WIDTH = 304f
    const val NOTICE_HEIGHT = 60f
    const val EXPANDED_WIDTH = 352f
    const val EXPANDED_HEIGHT = 196f
}
