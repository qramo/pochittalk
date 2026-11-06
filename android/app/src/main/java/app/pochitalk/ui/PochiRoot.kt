package app.pochitalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pochitalk.engine.UiState

/** UI から Activity (権限・共有) を呼ぶための窓口。 */
class Actions(
    val join: (String) -> Unit,
    val create: () -> Unit,
    val leave: () -> Unit,
    val share: () -> Unit,
    /** マイクテストの開始/停止 (開始時はマイク権限を確認する)。 */
    val micTest: (Boolean) -> Unit,
)

@Composable
fun PochiRoot(state: UiState, actions: Actions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(PochiColors.Bg)
            .safeDrawingPadding(),
    ) {
        if (state.currentKey == null) {
            HomeScreen(state, actions, Modifier.fillMaxSize())
        } else {
            RadioScreen(state, actions, Modifier.fillMaxSize())
        }
    }
}
