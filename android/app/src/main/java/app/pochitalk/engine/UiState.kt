package app.pochitalk.engine

/** 部屋一覧の1行 (ホーム画面・無線機画面の下に表示)。 */
data class RoomInfo(
    val key: String,
    val count: Int,
    val air: Boolean,
    val names: List<String>,
    val invited: Boolean,
    val isCurrent: Boolean,
    /** 話題タグと、付けた人の名前 (なければ空)。 */
    val tag: String = "",
    val tagBy: String = "",
)

data class PeerInfo(val id: String, val name: String, val talking: Boolean)

enum class StatusKind { STANDBY, TX, RX, CROSSTALK }

/** 液晶の音量レベルメーター (Web版の LEVEL / MIC / TX / RX 表示)。 */
data class MeterState(
    val label: String = "LEVEL",
    val level: Float = 0f,
    val peak: Float = 0f,
    val dbText: String = "-∞ dB",
)

data class UiState(
    val ready: Boolean = false,
    val nickname: String = "",
    /** 入室中の部屋ハッシュ (8桁の16進)。null ならホーム画面。 */
    val currentKey: String? = null,
    /** 招待リンクで指定された部屋 (まだ入室していない)。 */
    val pendingKey: String? = null,
    val rooms: List<RoomInfo> = emptyList(),
    val totalPeople: Int = 0,
    val peers: List<PeerInfo> = emptyList(),
    val status: StatusKind = StatusKind.STANDBY,
    val statusText: String = "STANDBY",
    val ledTx: Boolean = false,
    val ledRx: Boolean = false,
    val flash: String? = null,
    val voxOn: Boolean = false,
    val voxSens: Int = 4,
    val voxLevel: Float = 0f,
    val voxThreshold: Float = 0f,
    val soundOn: Boolean = true,
    /** 入力レベル (マイクの増幅)。0〜30 dB。 */
    val micGainDb: Int = 0,
    /** 設定のマイクテスト中 (入室していなくても入力レベルとメーターを確認できる)。 */
    val micTest: Boolean = false,
    /** 起動時に初回案内を表示するか。 */
    val welcomeOn: Boolean = true,
    /** 入室中の部屋の話題タグ。 */
    val roomTag: String = "",
    val roomTagBy: String = "",
    /** 再接続の途中 (ボタンを押せなくする)。 */
    val reconnecting: Boolean = false,
    val micGranted: Boolean = false,
)

/** UI (Activity) に頼む一回限りの操作。 */
sealed interface UiEvent {
    /** マイク権限のリクエストを出してほしい。 */
    data object RequestMic : UiEvent
}

fun formatKey(key: String): String = key.take(4).uppercase() + "·" + key.drop(4).uppercase()

fun isRoomKey(s: String?): Boolean = s != null && Regex("^[0-9a-f]{8}$").matches(s)
