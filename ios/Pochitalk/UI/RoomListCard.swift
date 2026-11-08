import SwiftUI

/// 部屋一覧のカード (ホーム画面・無線機画面の下)。ダイヤルと、人数・再接続。
struct RoomListCard: View {
    let ui: UiState
    var showReconnect: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("部屋を探す").font(.system(size: 16, weight: .bold)).foregroundStyle(PC.text)
                Spacer()
                Text("👤 \(ui.totalPeople)人").font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.sky)
            }
            RoomDial(
                rooms: ui.rooms,
                connected: ui.peers.count + 1, pendingKey: ui.pendingKey,
                emptyText: ui.ready ? "いま誰もいません。「新しい部屋を作る」で始められます" : "接続の準備中です…"
            ) { PochiEngine.shared.join($0) }
            Text("上下に回して、赤い線に合わせた部屋をタップすると入ります。")
                .font(.system(size: 11)).foregroundStyle(PC.muted)
            if showReconnect {
                Button(ui.reconnecting ? "再接続中…" : "一覧を再接続") { PochiEngine.shared.reconnect() }
                    .buttonStyle(SubButtonStyle(small: true)).disabled(ui.reconnecting)
            }
        }
    }
}
