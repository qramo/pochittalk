package app.pochitalk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pochitalk.engine.PochiEngine
import app.pochitalk.engine.UiState

/** ホーム画面 (ロビー): ハンドルネーム / 新しい部屋 / 設定 / 部屋一覧。Web版と同じ構成で、部屋一覧は普通に表示する。 */
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
                colors = pochiFieldColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (!it.isFocused) PochiEngine.commitNickname() },
            )
            SubButton(onClick = actions.create, modifier = Modifier.fillMaxWidth()) { Text("新しい部屋を作る") }
            SubButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth(), container = Color(0xFF1E293B)) {
                Text("⚙ 設定（マイク・このアプリについて）")
            }
        }
        PochiCard {
            RoomListCard(state, actions.join, showReconnect = true)
        }
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
