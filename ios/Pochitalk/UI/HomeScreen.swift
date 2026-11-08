import SwiftUI

/// ホーム画面 (ロビー): ハンドルネーム / 新しい部屋 / 設定 / 部屋一覧。
struct HomeScreen: View {
    let ui: UiState
    @ObservedObject var engine: PochiEngine
    @State private var showSettings = false
    @State private var showWelcome = false
    /// 初回案内は、アプリを起動したときに1回だけ出す (ホームに戻るたびには出さない)
    private static var welcomeShown = false
    @State private var nickDraft = ""
    @FocusState private var nickFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                PochiCard {
                    Brand()
                    VStack(alignment: .leading, spacing: 4) {
                        Text("ハンドルネーム").font(.system(size: 12)).foregroundStyle(PC.muted)
                        TextField("ハンドルネーム", text: $nickDraft)
                            .focused($nickFocused).pochiField().submitLabel(.done)
                            .onChange(of: nickDraft) { v in
                                let t = String(v.prefix(12))
                                if t != v { nickDraft = t }
                                engine.setNickname(t)
                            }
                            .onChange(of: nickFocused) { f in if !f { engine.commitNickname() } }
                            .onSubmit { nickFocused = false }
                    }
                    Button { engine.createRoom() } label: { Text("新しい部屋を作る").frame(maxWidth: .infinity) }
                        .buttonStyle(SubButtonStyle())
                    Button { showSettings = true } label: { Text("⚙ 設定（マイク・このアプリについて）").frame(maxWidth: .infinity) }
                        .buttonStyle(SubButtonStyle(container: PC.chip))
                    if ui.micPermission == .denied {
                        Text("マイクが許可されていません。設定アプリ → ぽちっと通話 → マイク で許可してください。")
                            .font(.system(size: 12)).foregroundStyle(PC.amber)
                    }
                }
                PochiCard { RoomListCard(ui: ui, showReconnect: true) }
                if let f = ui.flash {
                    Text(f).font(.system(size: 12)).foregroundStyle(PC.muted).multilineTextAlignment(.center)
                }
            }
            .padding(16)
        }
        .scrollDismissesKeyboardCompat()
        .onAppear {
            if nickDraft.isEmpty { nickDraft = ui.nickname }
            if !Self.welcomeShown { Self.welcomeShown = true; showWelcome = ui.welcomeOn }
        }
        .onChange(of: ui.nickname) { n in if nickDraft.isEmpty { nickDraft = n } }
        .sheet(isPresented: $showSettings) { SettingsView(ui: ui, engine: engine) }
        .sheet(isPresented: $showWelcome) { WelcomeView(ui: ui, engine: engine, onSettings: { showWelcome = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showSettings = true } }) }
    }
}

extension View {
    @ViewBuilder func scrollDismissesKeyboardCompat() -> some View {
        if #available(iOS 16.0, *) { self.scrollDismissesKeyboard(.interactively) } else { self }
    }
}

/// 初回案内 (起動したときだけ出す)。
struct WelcomeView: View {
    let ui: UiState
    @ObservedObject var engine: PochiEngine
    let onSettings: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showAbout = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("はじめにご確認ください。").foregroundStyle(PC.text)
                    Text("声が相手に十分な大きさで届くよう、入力レベルを調整できます。").font(.system(size: 13)).foregroundStyle(PC.text)
                    Button { onSettings() } label: { Text("マイクを設定する").frame(maxWidth: .infinity) }.buttonStyle(SubButtonStyle(container: PC.chip))
                    Text("使い方と、プライバシー（見える情報、保存されるもの）を説明しています。").font(.system(size: 13)).foregroundStyle(PC.text)
                    Button { showAbout = true } label: { Text("このアプリについて").frame(maxWidth: .infinity) }.buttonStyle(SubButtonStyle(container: PC.chip))
                    Toggle("次回も表示する", isOn: Binding(get: { ui.welcomeOn }, set: { engine.setWelcomeOn($0) })).foregroundStyle(PC.text)
                    Button { dismiss() } label: { Text("はじめる").frame(maxWidth: .infinity) }.buttonStyle(SubButtonStyle(container: Color(hex: 0x2563EB)))
                }.padding(16)
            }
            .background(PC.bg)
            .navigationTitle("ようこそ").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showAbout) { AboutView() }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }
}
