package app.pochitalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pochitalk.engine.PochiEngine
import app.pochitalk.engine.UiState

private val LinkStyle = TextLinkStyles(SpanStyle(color = Color(0xFF93C5FD), textDecoration = TextDecoration.Underline))

private fun linkText(label: String, url: String) = buildAnnotatedString {
    withLink(LinkAnnotation.Url(url, LinkStyle)) { append(label) }
}

/** 設定: マイク (入力レベル・マイクテスト) / 初回案内 / ソース・ライセンス / このアプリについて。Web版の設定ダイアログに対応。 */
@Composable
fun SettingsDialog(state: UiState, actions: Actions, onShowAbout: () -> Unit, onDismiss: () -> Unit) {
    // 設定を閉じたら、マイクテストを止める (Web版と同じ)
    DisposableEffect(Unit) {
        onDispose { PochiEngine.setMicTest(false) }
    }
    val meter by PochiEngine.meter.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PochiColors.Card)
                .border(2.dp, PochiColors.Border, RoundedCornerShape(20.dp)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("設定", color = PochiColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                SubButton(onClick = onDismiss, small = true) { Text("閉じる") }
            }
            HorizontalDivider(color = PochiColors.Border)
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionTitle("マイク")
                Text(
                    "「マイクテスト開始」を押して声を出し、メーターが振れることを確認してください。小さく聞こえる場合は、入力レベルを上げます（上げすぎると音が割れます）。",
                    color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp,
                )
                InputLevelSetting(state)
                LevelMeter(if (state.micTest) meter else meter.copy(label = "MIC", level = 0f, peak = 0f, dbText = "-∞ dB"))
                SubButton(
                    onClick = { actions.micTest(!state.micTest) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.micTest) "マイクテスト停止" else "マイクテスト開始") }

                SectionTitle("初回案内")
                SwitchRow("開いたときに初回案内を表示する", state.welcomeOn) { PochiEngine.setWelcomeOn(it) }

                SectionTitle("ソース・ライセンス")
                Text("このアプリは、qramo さんの「ぽっと通話」をもとに、ボタンを押している間だけ話せるようにしたものです。", color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
                Text(linkText("potalk.app", "https://potalk.app/"), fontSize = 13.sp)
                Text(linkText("github.com/qramo/po_call", "https://github.com/qramo/po_call"), fontSize = 13.sp)
                Text("このアプリのソースコードは GitHub で公開しています。", color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
                Text(linkText("github.com/riryo3/pochittalk", "https://github.com/riryo3/pochittalk"), fontSize = 13.sp)
                Text("MIT License\nCopyright (c) 2026 qramo\nCopyright (c) 2026 リョータ", color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
                Text(
                    buildAnnotatedString {
                        append("使用ライブラリ: ")
                        withLink(LinkAnnotation.Url("https://github.com/dmotz/trystero", LinkStyle)) { append("Trystero") }
                        append("（MIT License。通信方式を Kotlin に移植）、")
                        withLink(LinkAnnotation.Url("https://github.com/GetStream/webrtc-android", LinkStyle)) { append("stream-webrtc-android") }
                        append("（Apache License 2.0）、")
                        withLink(LinkAnnotation.Url("https://github.com/square/okhttp", LinkStyle)) { append("OkHttp") }
                        append("（Apache License 2.0）")
                    },
                    color = PochiColors.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                )

                SectionTitle("このアプリについて")
                SubButton(onClick = onShowAbout, modifier = Modifier.fillMaxWidth(), container = Color(0xFF1E293B)) { Text("使い方・プライバシーを見る") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color(0xFF93C5FD), fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
    HorizontalDivider(color = PochiColors.Border)
}

/** 初回案内。起動時に出す (設定でオフにできる)。 */
@Composable
fun WelcomeDialog(state: UiState, onSettings: () -> Unit, onAbout: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PochiColors.Card)
                .border(2.dp, PochiColors.Border, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ようこそ", color = PochiColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "α版",
                    color = PochiColors.Amber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .border(1.dp, PochiColors.Amber, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text("はじめにご確認ください。", color = PochiColors.Text, fontSize = 13.sp)
            SectionTitle("1. マイクを設定する")
            Text("声が相手に十分な大きさで届くよう、入力レベルを調整できます。", color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
            SubButton(onClick = onSettings, modifier = Modifier.fillMaxWidth(), container = Color(0xFF1E293B)) { Text("マイクを設定する") }
            SectionTitle("2. このアプリについて読む")
            Text("使い方と、プライバシー（見える情報、保存されるもの）を説明しています。", color = PochiColors.Text, fontSize = 13.sp, lineHeight = 20.sp)
            SubButton(onClick = onAbout, modifier = Modifier.fillMaxWidth(), container = Color(0xFF1E293B)) { Text("このアプリについて") }
            SwitchRow("次回も表示する", state.welcomeOn) { PochiEngine.setWelcomeOn(it) }
            SubButton(onClick = onClose, modifier = Modifier.fillMaxWidth(), container = Color(0xFF2563EB)) { Text("はじめる") }
        }
    }
}
