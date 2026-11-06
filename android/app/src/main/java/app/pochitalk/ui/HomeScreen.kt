package app.pochitalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import app.pochitalk.engine.PochiEngine
import app.pochitalk.engine.UiState

/** ホーム画面: ハンドルネーム / 新しい部屋 / 設定 / 部屋一覧。Web版と同じ構成。 */
@Composable
fun HomeScreen(state: UiState, actions: Actions, modifier: Modifier = Modifier) {
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // 起動したときだけ初回案内を出す (画面の回転では出し直さない)
    var showWelcome by rememberSaveable { mutableStateOf(state.welcomeOn) }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PochiCard {
            Brand()
            OutlinedTextField(
                value = state.nickname,
                onValueChange = { if (it.length <= 12) PochiEngine.setNickname(it) },
                label = { Text("ハンドルネーム") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) PochiEngine.commitNickname() },
            )
            Button(onClick = actions.create, modifier = Modifier.fillMaxWidth()) { Text("新しい部屋を作る") }
            OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                Text("⚙ 設定（マイク・このアプリについて）")
            }
        }
        RoomListCard(state, actions.join)
    }
    if (showWelcome) {
        WelcomeDialog(
            state,
            onSettings = { showWelcome = false; showSettings = true },
            onAbout = { showWelcome = false; showAbout = true },
            onClose = { showWelcome = false },
        )
    }
    if (showSettings) {
        SettingsDialog(state, actions, onShowAbout = { showAbout = true }, onDismiss = { showSettings = false })
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}
