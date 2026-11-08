import SwiftUI

/// 無線機画面: Web 版と同じく、1つの「無線機」カードの中に、ヘッダー / 液晶 (ROOM HASH・レベル・話題タグ・状態・参加者) /
/// 話すボタン / 招待・再接続・VOX・効果音 を並べる。部屋一覧は、無線機の下に並べる。
struct RadioScreen: View {
    let ui: UiState
    @ObservedObject var engine: PochiEngine
    @State private var showQr = false

    var body: some View {
        guard let key = ui.currentKey else { return AnyView(EmptyView()) }
        return AnyView(
            ScrollView {
                VStack(spacing: 16) {
                    deviceCard(key)
                    PochiCard { RoomListCard(ui: ui, showReconnect: false) }
                    Spacer().frame(height: 24)
                }
                .padding(16)
            }
            .onChange(of: key) { _ in showQr = false }
        )
    }

    private func deviceCard(_ key: String) -> some View {
        VStack(spacing: 20) {
            if showQr {
                QrBackFace(roomKey: key) { withAnimation { showQr = false } }
            } else {
                VStack(spacing: 12) {
                    HStack {
                        Brand()
                        Spacer()
                        HStack(spacing: 6) {
                            Button(ui.reconnecting ? "再接続中…" : "再接続") { engine.reconnect() }
                                .buttonStyle(SubButtonStyle(small: true)).disabled(ui.reconnecting)
                            Button("退出") { engine.leave() }.buttonStyle(SubButtonStyle(small: true))
                        }
                    }
                    Rectangle().fill(PC.border).frame(height: 2)
                }
                DisplayPanel(ui: ui, key: key, meter: engine.meter)
                PttSection(ui: ui)
                footer(key)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(PC.card, in: RoundedRectangle(cornerRadius: 28))
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(PC.border, lineWidth: 2))
        .shadow(color: .black.opacity(0.4), radius: 14, y: 6)
    }

    private func footer(_ key: String) -> some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                if let url = engine.inviteUrl(for: key).flatMap(URL.init(string:)) {
                    ShareLink(item: url, message: Text("ぽちっと通話の部屋に入ろう")) {
                        Text("招待URLを送る").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(SubButtonStyle())
                }
                Button { withAnimation { showQr = true } } label: { QrIcon().frame(width: 40) }
                    .buttonStyle(SubButtonStyle())
                    .accessibilityLabel("QRコードで招待")
                Button { engine.toggleSpeakerMute() } label: { Text(ui.speakerMuted ? "🔇" : "🔈").font(.system(size: 18)).frame(width: 40) }
                    .buttonStyle(SubButtonStyle(container: ui.speakerMuted ? Color(hex: 0x7F1D1D) : Color(hex: 0x374151)))
                    .accessibilityLabel(ui.speakerMuted ? "スピーカーのミュートを解除" : "スピーカーをミュート")
            }
            VoxSection(ui: ui, engine: engine)
        }
    }
}

/// QR ボタンの絵 (三つの角の四角と、右下の点)。
struct QrIcon: View {
    var body: some View {
        Canvas { ctx, size in
            let u = size.width / 24
            for (x, y) in [(3.0, 3.0), (14.0, 3.0), (3.0, 14.0)] {
                ctx.stroke(Path(roundedRect: CGRect(x: x * u, y: y * u, width: 7 * u, height: 7 * u), cornerRadius: u), with: .color(.white), lineWidth: 2 * u)
            }
            for (x, y, w) in [(14.0, 14.0, 3.0), (19.0, 14.0, 2.0), (14.0, 19.0, 2.0), (18.0, 18.0, 3.0)] {
                ctx.fill(Path(CGRect(x: x * u, y: y * u, width: w * u, height: w * u)), with: .color(.white))
            }
        }.frame(width: 22, height: 22)
    }
}

/// 液晶: ROOM HASH / レベルメーター / 話題タグ / TX・RX・状態 / 参加者 (Web 版の .display)。
private struct DisplayPanel: View {
    let ui: UiState
    let key: String
    let meter: MeterState

    var body: some View {
        VStack(spacing: 12) {
            VStack(spacing: 2) {
                Text("ROOM HASH").font(.system(size: 12, design: .monospaced)).tracking(3).foregroundStyle(PC.muted)
                Text(formatKey(key)).font(.system(size: 38, weight: .black, design: .monospaced)).foregroundStyle(PC.sky)
                    .minimumScaleFactor(0.6).lineLimit(1)
                Text(ui.flash ?? (ui.rxText.isEmpty ? " " : ui.rxText)).font(.system(size: 12, design: ui.flash == nil && !ui.rxText.isEmpty ? .monospaced : .default))
                    .foregroundStyle(ui.flash == nil && !ui.rxText.isEmpty ? PC.sky : PC.muted).multilineTextAlignment(.center)
                    .frame(minHeight: 18)
                if let paper = ui.faxPaper { FaxPaperView(paper: paper) }
                LevelMeter(meter: meter)
            }
            TagRow(ui: ui)
            HStack {
                HStack(spacing: 14) {
                    HStack(spacing: 4) { Led(on: ui.ledTx, color: PC.tx); Text("TX").font(.system(size: 11, weight: .heavy)).foregroundStyle(PC.muted) }
                    HStack(spacing: 4) { Led(on: ui.ledRx, color: PC.rx); Text("RX").font(.system(size: 11, weight: .heavy)).foregroundStyle(PC.muted) }
                }
                Spacer()
                Text(ui.statusText).font(.system(size: 16, weight: .bold, design: .monospaced)).foregroundStyle(statusColor)
            }
            Divider().overlay(Color(hex: 0x2D3748))
            peers
            Text("接続 \(ui.connN) / 在室 \(ui.connM)").font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundStyle(ui.connN >= ui.connM ? PC.botGreen : Color(hex: 0xFACC15))
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(16)
        .background(PC.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(hex: 0x22272E), lineWidth: 2))
    }

    private var statusColor: Color {
        switch ui.status {
        case .tx: return PC.tx
        case .rx: return PC.rx
        case .crosstalk: return Color(hex: 0xF59E0B)
        case .standby: return PC.sky
        }
    }

    private var peers: some View {
        let myName = ui.nickname.trimmingCharacters(in: .whitespaces).isEmpty ? "NoName" : ui.nickname.trimmingCharacters(in: .whitespaces)
        return FlowLayout(spacing: 4) {
            chip((ui.ledTx ? "🎙️ " : "") + myName + "（自分）", fg: ui.ledTx ? Color(hex: 0xFECACA) : Color(hex: 0xBFDBFE),
                 bg: ui.ledTx ? Color(hex: 0x7F1D1D) : Color(hex: 0x172554), bold: ui.ledTx)
            ForEach(ui.peers) { p in
                chip((p.talking ? "🎙️ " : "") + p.name, fg: p.talking ? PC.botGreen : PC.muted, bg: p.talking ? Color(hex: 0x064E3B) : PC.chip, bold: p.talking)
            }
            if ui.peers.isEmpty {
                Text("（ほかの参加者を待っています）").font(.system(size: 13)).foregroundStyle(PC.muted).opacity(0.6).padding(.vertical, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chip(_ text: String, fg: Color, bg: Color, bold: Bool) -> some View {
        Text(text).font(.system(size: 13, weight: bold ? .bold : .regular)).foregroundStyle(fg)
            .padding(.horizontal, 8).padding(.vertical, 1).background(bg, in: RoundedRectangle(cornerRadius: 6))
    }
}

/// 折り返して並べるレイアウト (参加者の名札用)。
struct FlowLayout: Layout {
    var spacing: CGFloat = 4
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 300
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > 0 && x + sz.width > w { x = 0; y += rowH + spacing; rowH = 0 }
            x += sz.width + spacing; rowH = max(rowH, sz.height); maxX = max(maxX, x)
        }
        return CGSize(width: min(w, maxX), height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + sz.width > bounds.maxX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(sz))
            x += sz.width + spacing; rowH = max(rowH, sz.height)
        }
    }
}

/// 音量レベルメーター (Web 版の LEVEL / MIC / TX / RX)。
struct LevelMeter: View {
    let meter: MeterState
    private var idle: Bool { meter.label == "MIC" || meter.label == "LEVEL" }
    var body: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4).fill(Color(hex: 0x0B0E12))
                    RoundedRectangle(cornerRadius: 4)
                        .fill(LinearGradient(colors: [PC.rx, PC.amber, PC.tx], startPoint: .leading, endPoint: .trailing))
                        .frame(width: geo.size.width * CGFloat(meter.level))
                        .opacity(idle ? 0.3 : 1) // 待機中 (MIC / LEVEL) は薄く。TX / RX のときだけ、はっきり
                    Rectangle().fill(Color.white.opacity(0.85)).frame(width: 2)
                        .offset(x: max(0, geo.size.width * CGFloat(meter.peak) - 2))
                        .opacity(meter.peak > 0.01 ? 1 : 0)
                }
            }
            .frame(height: 10)
            HStack {
                Text(meter.label).font(.system(size: 10, design: .monospaced)).foregroundStyle(PC.muted)
                Spacer()
                Text(meter.dbText).font(.system(size: 10, design: .monospaced)).foregroundStyle(PC.muted)
            }
        }
        .padding(.top, 4)
    }
}

/// 話題タグの行: 誰でもつけられ、付けた人の名前が添えられる。部屋の一覧にも出る。
private struct TagRow: View {
    let ui: UiState
    @State private var editing = false
    @State private var draft = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                label.lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                // おうむ返しボットの部屋は、説明のタグを守るため、編集できない
                if ui.currentKey != botRoomKey {
                    Button(ui.roomTag.isEmpty ? "つける" : "変える") {
                        editing.toggle()
                        if editing { draft = ui.roomTag; focused = true }
                    }.buttonStyle(SubButtonStyle(small: true))
                }
            }
            if editing {
                HStack(spacing: 8) {
                    TextField("いまの話題（例：ゲームの話）", text: $draft)
                        .focused($focused).pochiField().submitLabel(.done)
                        .onChange(of: draft) { v in if v.unicodeScalars.count > 30 { draft = String(String.UnicodeScalarView(v.unicodeScalars.prefix(30))) } }
                        .onSubmit(commit)
                    Button("確定", action: commit).buttonStyle(SubButtonStyle())
                }
                Text("\(draft.unicodeScalars.count) / 30 文字").font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(draft.unicodeScalars.count >= 30 ? PC.amber : PC.muted).frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private var label: Text {
        if ui.roomTag.isEmpty { return Text("🏷 話題タグ：まだありません").font(.system(size: 13)).foregroundColor(PC.muted) }
        var t = Text("🏷 ").font(.system(size: 13)).foregroundColor(PC.muted) + Text(ui.roomTag).font(.system(size: 13, weight: .bold)).foregroundColor(PC.text)
        if !ui.roomTagBy.isEmpty { t = t + Text("  （\(ui.roomTagBy)が付けた）").font(.system(size: 11)).foregroundColor(PC.muted.opacity(0.8)) }
        return t
    }

    private func commit() {
        PochiEngine.shared.setTag(draft)
        editing = false
        focused = false
    }
}

/// 話すボタン (押している間だけ話す) と、VOX の丸ボタン。
private struct PttSection: View {
    let ui: UiState
    private let circle: CGFloat = 170
    @State private var gesture: PttGesture?
    @State private var holdTimer: DispatchWorkItem?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let circleLeft: CGFloat = {
                switch ui.pttPos {
                case .left: return 0
                case .center: return (w - circle) / 2
                case .right: return w - circle
                }
            }()
            let voxOnLeft: Bool = {
                switch ui.pttPos {
                case .left: return false
                case .right: return true
                case .center: return !ui.lefty
                }
            }()
            let voxLeft = voxOnLeft ? max(0, circleLeft - 65) : min(w - 62, circleLeft + circle + 3)
            ZStack(alignment: .topLeading) {
                pttCircle.offset(x: circleLeft)
                VoxButton(on: ui.voxOn).offset(x: voxLeft, y: circle - 62 + 14)
            }
        }
        .frame(height: circle + 14)
    }

    private var pttCircle: some View {
        let tx = ui.ledTx
        return ZStack {
            Circle().fill(RadialGradient(colors: tx ? [Color(hex: 0xEF4444), Color(hex: 0xB91C1C)] : [Color(hex: 0x3B82F6), Color(hex: 0x1D4ED8)],
                                         center: .center, startRadius: 0, endRadius: 100))
            Circle().stroke(tx ? Color(hex: 0x7F1D1D) : Color(hex: 0x1E3A8A), lineWidth: 6)
            VStack(spacing: 2) {
                Text("PUSH").font(.system(size: 21, weight: .black)).foregroundStyle(.white)
                Text("TO TALK").font(.system(size: 12)).tracking(2).foregroundStyle(.white.opacity(0.85))
            }
        }
        .frame(width: circle, height: circle)
        .scaleEffect(tx ? 0.96 : 1)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .local)
                .onChanged { v in
                    let now = ProcessInfo.processInfo.systemUptime * 1000
                    if gesture == nil {
                        var g = PttGesture(center: CGPoint(x: circle / 2, y: circle / 2), outsideRadius: circle / 2 + 14)
                        g.onDown(now, v.location)
                        gesture = g
                        scheduleHold()
                    } else {
                        var g = gesture!
                        let a = g.onMove(now, v.location)
                        gesture = g
                        apply(a)
                    }
                }
                .onEnded { _ in
                    holdTimer?.cancel()
                    if var g = gesture {
                        let a = g.onUp()
                        apply(a)
                    }
                    gesture = nil
                }
        )
        .accessibilityLabel("話すボタン")
        .accessibilityAddTraits(.isButton)
    }

    private func scheduleHold() {
        holdTimer?.cancel()
        let item = DispatchWorkItem {
            guard var g = gesture else { return }
            let a = g.onTimer(ProcessInfo.processInfo.systemUptime * 1000)
            gesture = g
            apply(a)
        }
        holdTimer = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.055, execute: item)
    }

    private func apply(_ a: PttGesture.Action) {
        switch a {
        case .start: PochiEngine.shared.pttPress()
        case .release: PochiEngine.shared.pttRelease()
        case .abort: PochiEngine.shared.pttAbort()
        case .swipe, .none: break
        }
    }
}

/// VOX の丸ボタン: オフは灰色、オンは緑に光る。
private struct VoxButton: View {
    let on: Bool
    var body: some View {
        Button { PochiEngine.shared.setVox(!on) } label: {
            VStack(spacing: 0) {
                Text("VOX").font(.system(size: 13, weight: .heavy)).tracking(1)
                Text(on ? "ON" : "OFF").font(.system(size: 10, weight: .heavy)).opacity(0.85)
            }
            .foregroundStyle(on ? Color(hex: 0x052E1C) : PC.muted)
            .frame(width: 62, height: 62)
            .background(on ? AnyShapeStyle(RadialGradient(colors: [Color(hex: 0x34D399), Color(hex: 0x059669)], center: .center, startRadius: 0, endRadius: 40)) : AnyShapeStyle(Color(hex: 0x1F2937)), in: Circle())
            .overlay(Circle().stroke(on ? Color(hex: 0x065F46) : Color(hex: 0x374151), lineWidth: 3))
            .shadow(color: on ? Color(hex: 0x10B981).opacity(0.6) : .clear, radius: 10)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(on ? "VOXをオフにする" : "VOXをオンにする")
    }
}

private struct VoxSection: View {
    let ui: UiState
    @ObservedObject var engine: PochiEngine
    var body: some View {
        if ui.voxOn {
            VStack(alignment: .leading, spacing: 8) {
                // メーター: 緑 = 入力レベル、黄色の線 = しきい値。しきい値を超えると赤
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        RoundedRectangle(cornerRadius: 5).fill(PC.panel)
                        Rectangle().fill(ui.voxLevel > ui.voxThreshold ? PC.tx : PC.rx).frame(width: geo.size.width * CGFloat(min(1, max(0, ui.voxLevel))))
                        Rectangle().fill(PC.amber).frame(width: 2).offset(x: geo.size.width * CGFloat(min(1, max(0, ui.voxThreshold))))
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 5))
                    .overlay(RoundedRectangle(cornerRadius: 5).stroke(PC.border, lineWidth: 1))
                }.frame(height: 10)
                HStack(spacing: 8) {
                    Text("しきい値").font(.system(size: 13)).foregroundStyle(PC.muted)
                    Slider(value: Binding(get: { Double(ui.voxSens) }, set: { engine.setVoxSensitivity(Int($0.rounded())) }), in: 1...10, step: 1)
                }
                Text("スピーカーの音を拾いやすいので、イヤホンがおすすめです。ロック画面でも、通話中は VOX が使えます。入力レベルは、ホームの「設定」で変えられます。")
                    .font(.system(size: 11)).foregroundStyle(PC.muted)
            }
        }
    }
}


/// 受信した FAX の紙 (240×15 の白黒を拡大して出す。Web 版の faxCanvas)。
private struct FaxPaperView: View {
    let paper: FaxPaper

    var body: some View {
        Canvas { ctx, size in
            let w = size.width / CGFloat(FaxPaper.cols), h = size.height / CGFloat(FaxPaper.rows)
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(red: 248 / 255, green: 248 / 255, blue: 240 / 255)))
            for y in 0..<FaxPaper.rows {
                var x = 0
                while x < FaxPaper.cols {
                    if paper.pixels[y * FaxPaper.cols + x] == 0 { x += 1; continue }
                    var x2 = x
                    while x2 < FaxPaper.cols && paper.pixels[y * FaxPaper.cols + x2] == 1 { x2 += 1 }
                    ctx.fill(Path(CGRect(x: CGFloat(x) * w, y: CGFloat(y) * h, width: CGFloat(x2 - x) * w, height: h)),
                             with: .color(Color(red: 20 / 255, green: 20 / 255, blue: 20 / 255)))
                    x = x2
                }
            }
        }
        .aspectRatio(CGFloat(FaxPaper.cols) / CGFloat(FaxPaper.rows), contentMode: .fit)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .padding(.top, 6)
        .accessibilityLabel("受信したFAX")
    }
}
