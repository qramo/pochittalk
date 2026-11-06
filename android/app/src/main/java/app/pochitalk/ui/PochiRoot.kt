package app.pochitalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pochitalk.engine.UiState
import kotlinx.coroutines.launch

/** UI から Activity (権限・共有) を呼ぶための窓口。 */
class Actions(
    val join: (String) -> Unit,
    val create: () -> Unit,
    val leave: () -> Unit,
    val share: () -> Unit,
    /** マイクテストの開始/停止 (開始時はマイク権限を確認する)。 */
    val micTest: (Boolean) -> Unit,
)

/**
 * 画面の土台。ロビー (ホーム) では部屋一覧を普通に表示する。入室後は、部屋一覧を下に並べず、
 * 左から右へスワイプすると開く左ドロワーにする (画面の左端の小さなつまみをタップしても開く)。
 */
@Composable
fun PochiRoot(state: UiState, actions: Actions) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openRooms: () -> Unit = { scope.launch { drawerState.open() } }
    val inRoom = state.currentKey != null
    // 退出してロビーに戻ったら、開いていたドロワーは閉じる
    LaunchedEffect(inRoom) { if (!inRoom) drawerState.snapTo(DrawerValue.Closed) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = inRoom, // スワイプで開けるのは入室後だけ
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = PochiColors.Bg, modifier = Modifier.width(330.dp)) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    PochiCard {
                        RoomListCard(
                            state,
                            onJoin = { key ->
                                actions.join(key)
                                scope.launch { drawerState.close() }
                            },
                            showReconnect = true,
                        )
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(PochiColors.Bg)
                .safeDrawingPadding(),
        ) {
            if (!inRoom) {
                HomeScreen(state, actions, modifier = Modifier.fillMaxSize())
            } else {
                RadioScreen(state, actions, modifier = Modifier.fillMaxSize())
            }
            // 左端のつまみ (入室後だけ): ここをタップしても部屋一覧が開く。スワイプできることの目印
            if (inRoom) Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(16.dp)
                    .height(88.dp)
                    .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                    .background(PochiColors.ChipBorder.copy(alpha = 0.85f))
                    .clickable { openRooms() },
                contentAlignment = Alignment.Center,
            ) {
                Text("›", color = PochiColors.Text, fontSize = 16.sp)
            }
        }
    }
}
