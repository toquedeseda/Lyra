package com.lyra.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Error(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

fun friendlyError(error: Throwable): String = when (error) {
    is UnknownHostException -> "Sin conexión a internet"
    is SocketTimeoutException -> "La conexión tardó demasiado"
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Algo ha fallado"
}

/** ViewModel que carga un único valor con estados de carga y error. */
abstract class LoadViewModel<T> : ViewModel() {
    private val _state = MutableStateFlow<Loadable<T>>(Loadable.Loading)
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()

    protected abstract suspend fun fetch(): T

    fun load() {
        viewModelScope.launch {
            if (_state.value !is Loadable.Ready) _state.value = Loadable.Loading
            _state.value = runCatching { Loadable.Ready(fetch()) }.getOrElse { Loadable.Error(friendlyError(it)) }
        }
    }

    protected fun update(transform: (T) -> T) {
        val current = _state.value as? Loadable.Ready ?: return
        _state.value = Loadable.Ready(transform(current.value))
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LyraColors.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyView(
        icon = Icons.Rounded.CloudOff,
        title = "No se pudo cargar",
        message = message,
        modifier = modifier,
        action = "Reintentar",
        onAction = onRetry,
    )
}

/** Estado vacío con el estilo de la web: tarjeta oscura, texto gris y botón hueso. */
@Composable
fun EmptyView(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LyraColors.SurfaceHigh)
            .border(1.dp, LyraColors.Border, RoundedCornerShape(16.dp))
            .padding(18.dp),
    ) {
        Icon(icon, null, tint = LyraColors.TextTertiary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            PillButton(action, onAction)
        }
    }
}
