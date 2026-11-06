package app.pochitalk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pochitalk.engine.MeterState
import app.pochitalk.engine.PeerInfo
import app.pochitalk.engine.PochiEngine
import app.pochitalk.engine.StatusKind
import app.pochitalk.engine.UiState
import app.pochitalk.engine.formatKey
import kotlin.math.abs

/** 無線機画面: 液晶 / 状態 / 参加者 / 話すボタン / 招待 / VOX / 効果音 / 部屋一覧。 */
@Composable
fun RadioScreen(state: UiState, actions: Actions, modifier: Modifier = Modifier) {
    val key = state.currentKey ?: return
    Column(modifier.fillMaxWidth()) {
        // 上部バー (スクロールしても常にルームハッシュと退出ボタンが見える)
        Row(
            Modifier
                .fillMaxWidth()
                .background(PochiColors.Panel)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Brand()
                Text(
                    formatKey(key),
                    color = PochiColors.Sky,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                )
            }
            OutlinedButton(onClick = actions.leave) { Text("退出") }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val meter by PochiEngine.meter.collectAsStateWithLifecycle()
            Lcd(state, key, meter)
            PeersCard(state.peers)
            PttSection(transmitting = state.ledTx)
            PochiCard {
                OutlinedButton(onClick = actions.share, modifier = Modifier.fillMaxWidth()) { Text("招待URLを送る") }
                VoxSection(state)
                SwitchRow("無線効果音（スケルチ音など）を鳴らす", state.soundOn) { PochiEngine.setSound(it) }
            }
            RoomListCard(state, actions.join)
        }
    }
}

@Composable
private fun Lcd(state: UiState, key: String, meter: MeterState) {
    PochiCard(background = PochiColors.Panel) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ROOM HASH", color = PochiColors.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 3.sp)
            Text(
                formatKey(key),
                color = PochiColors.Sky,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 38.sp,
            )
            Text(state.flash ?: "", color = PochiColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.height(18.dp))
            LevelMeter(meter)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Led(state.ledTx, PochiColors.Tx)
                    Text("TX", color = PochiColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Led(state.ledRx, PochiColors.Rx)
                    Text("RX", color = PochiColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            val statusColor = when (state.status) {
                StatusKind.TX -> PochiColors.Tx
                StatusKind.RX -> PochiColors.Rx
                StatusKind.CROSSTALK -> Color(0xFFF59E0B)
                StatusKind.STANDBY -> PochiColors.Sky
            }
            Text(state.statusText, color = statusColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeersCard(peers: List<PeerInfo>) {
    PochiCard(background = PochiColors.Panel) {
        if (peers.isEmpty()) {
            Text("待機中（参加者なし）", color = PochiColors.Muted, fontSize = 13.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                peers.forEach { p ->
                    Text(
                        (if (p.talking) "🎙️ " else "👤 ") + p.name,
                        color = if (p.talking) Color(0xFF34D399) else PochiColors.Muted,
                        fontWeight = if (p.talking) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (p.talking) Color(0xFF064E3B) else PochiColors.Chip)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PttSection(transmitting: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val brush = if (transmitting) {
            Brush.radialGradient(listOf(Color(0xFFEF4444), Color(0xFFB91C1C)))
        } else {
            Brush.radialGradient(listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8)))
        }
        Box(
            Modifier
                .size(170.dp)
                .scale(if (transmitting) 0.96f else 1f)
                .clip(CircleShape)
                .background(brush)
                .border(6.dp, if (transmitting) Color(0xFF7F1D1D) else Color(0xFF1E3A8A), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        PochiEngine.pttPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            PochiEngine.pttRelease()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("POCHI", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
                Text("TALK", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, letterSpacing = 2.sp)
            }
        }
        Text("ボタンを押している間だけ話せます", color = PochiColors.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun VoxSection(state: UiState) {
    SwitchRow("VOX（声を拾って自動で送信）", state.voxOn) { PochiEngine.setVox(it) }
    if (state.voxOn) {
        // メーター: 緑 = 入力レベル、黄色の線 = しきい値。しきい値を超えると赤
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(PochiColors.Panel)
                .border(1.dp, PochiColors.Border, RoundedCornerShape(5.dp)),
        ) {
            val over = state.voxLevel > state.voxThreshold
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(state.voxLevel.coerceIn(0f, 1f))
                    .background(if (over) PochiColors.Tx else PochiColors.Rx),
            )
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.fillMaxWidth(state.voxThreshold.coerceIn(0f, 1f)))
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(PochiColors.Amber),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("しきい値", color = PochiColors.Muted, fontSize = 13.sp)
            Slider(
                value = state.voxSens.toFloat(),
                onValueChange = { PochiEngine.setVoxSensitivity(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "スピーカーの音を拾いやすいので、イヤホンがおすすめです。入力レベルは、ホームの「設定」で変えられます。",
            color = PochiColors.Muted,
            fontSize = 11.sp,
        )
    }
}
