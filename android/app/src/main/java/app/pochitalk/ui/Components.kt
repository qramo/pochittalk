package app.pochitalk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import app.pochitalk.engine.MeterState
import app.pochitalk.engine.PochiEngine
import app.pochitalk.engine.RoomInfo
import app.pochitalk.engine.UiState
import app.pochitalk.engine.formatKey

@Composable
fun PochiCard(
    modifier: Modifier = Modifier,
    background: Color = PochiColors.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(BorderStroke(2.dp, PochiColors.Border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ぽちっと通話", color = Color(0xFF93C5FD), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text(
            "α版",
            color = PochiColors.Amber,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .border(1.dp, PochiColors.Amber, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun Led(on: Boolean, color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (on) color else PochiColors.LedOff),
    )
}

/** 「いまある部屋」の一覧。ホーム画面と無線機画面の両方に出す。 */
@Composable
fun RoomListCard(state: UiState, onJoin: (String) -> Unit) {
    PochiCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("いまある部屋（タップで入る）", color = PochiColors.Muted, fontSize = 12.sp)
            Text(
                "👥 ${state.totalPeople}人 / ${state.rooms.size}部屋",
                color = PochiColors.Muted,
                fontSize = 12.sp,
            )
        }
        if (state.rooms.isEmpty()) {
            Text(
                if (state.ready) "いま誰もいません。「新しい部屋を作る」で始めましょう" else "接続中...",
                color = PochiColors.Muted,
                fontSize = 13.sp,
            )
        }
        state.rooms.forEach { room -> RoomRow(room, onJoin) }
    }
}

@Composable
private fun RoomRow(room: RoomInfo, onJoin: (String) -> Unit) {
    val border = when {
        room.air -> PochiColors.Tx
        room.isCurrent -> PochiColors.Accent
        else -> PochiColors.ChipBorder
    }
    val bg = if (room.isCurrent) Color(0xFF172554) else PochiColors.Chip
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = !room.isCurrent) { onJoin(room.key) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val label = (if (room.air) "🔴 " else "🔵 ") + formatKey(room.key) + " · 👤${room.count}" +
            (if (room.invited) " · 招待" else "") + (if (room.isCurrent) " · 入室中" else "")
        Text(label, color = PochiColors.Text, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        val who = if (room.invited) "招待された部屋（現在は無人）" else room.names.joinToString("、")
        if (who.isNotEmpty()) Text(who, color = PochiColors.Muted, fontSize = 12.sp)
    }
}

/** "**太字**" を太字にする簡単な変換。 */
fun boldMarkup(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(part) }
        else append(part)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, color = PochiColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** 入力レベル (マイクの増幅 0〜30dB)。ホームの基本設定と、無線機画面の両方に出す (同じ設定)。 */
@Composable
fun InputLevelSetting(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("入力レベル（マイクの増幅）", color = PochiColors.Text, fontSize = 13.sp)
            Text(
                if (state.micGainDb == 0) "そのまま" else "+${state.micGainDb} dB",
                color = PochiColors.Sky,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        Slider(
            value = state.micGainDb.toFloat(),
            onValueChange = { PochiEngine.setMicGainDb(it.toInt()) },
            valueRange = 0f..30f,
            steps = 9,
        )
        Text(
            "相手に声が小さく聞こえるときに右へ。上げすぎると割れます（+6dBで約2倍、+20dBで10倍）。端末ごとに保存されます。",
            color = PochiColors.Muted,
            fontSize = 11.sp,
        )
    }
}

/** Web版の LEVEL 表示: 24セグメント、緑→黄→赤、ピークホールド付き。 */
@Composable
fun LevelMeter(meter: MeterState) {
    val segs = 24
    val labelColor = when (meter.label) {
        "TX" -> PochiColors.Tx
        "RX" -> PochiColors.Rx
        "MIC" -> PochiColors.Accent
        else -> PochiColors.Muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            meter.label,
            color = labelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.width(38.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp),
        ) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (segs - 1)) / segs
            for (i in 0 until segs) {
                val pos = (i + 1f) / segs
                val on = pos <= meter.level + 0.001f
                val isPeak = abs(pos - meter.peak) < 0.5f / segs + 0.001f && meter.peak > 0.02f
                val col = when {
                    pos > 0.85f -> PochiColors.Tx
                    pos > 0.65f -> PochiColors.Amber
                    else -> PochiColors.Rx
                }
                drawRect(
                    color = col.copy(alpha = if (on || isPeak) 1f else 0.16f),
                    topLeft = Offset(i * (w + gap), 0f),
                    size = Size(w, size.height),
                )
            }
        }
        Text(
            meter.dbText,
            color = PochiColors.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
    }
}

