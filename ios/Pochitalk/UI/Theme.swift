import SwiftUI

/// Web 版 (worker.js の :root) と同じ配色。
enum PC {
    static let bg = Color(hex: 0x1A1E24)
    static let card = Color(hex: 0x252B36)
    static let panel = Color(hex: 0x14181D)
    static let accent = Color(hex: 0x3B82F6)
    static let tx = Color(hex: 0xEF4444)
    static let rx = Color(hex: 0x10B981)
    static let text = Color(hex: 0xF3F4F6)
    static let muted = Color(hex: 0x9CA3AF)
    static let border = Color(hex: 0x374151)
    static let sky = Color(hex: 0x38BDF8)
    static let amber = Color(hex: 0xFBBF24)
    static let chip = Color(hex: 0x1E293B)
    static let chipBorder = Color(hex: 0x334155)
    static let ledOff = Color(hex: 0x374151)
    static let botGreen = Color(hex: 0x34D399)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255, opacity: alpha)
    }
}

/// 無線機の外枠や、一覧を載せるカード。
struct PochiCard<Content: View>: View {
    var corner: CGFloat = 20
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PC.card, in: RoundedRectangle(cornerRadius: corner))
            .overlay(RoundedRectangle(cornerRadius: corner).stroke(PC.border, lineWidth: 2))
    }
}

/// 小さめの四角いボタン (Web 版の .sub-btn)。
struct SubButtonStyle: ButtonStyle {
    var container: Color = Color(hex: 0x374151)
    var small = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: small ? 12 : 14, weight: .bold))
            .foregroundStyle(PC.text)
            .padding(.horizontal, small ? 10 : 14)
            .padding(.vertical, small ? 6 : 11)
            .frame(minHeight: small ? 30 : 44)
            .background(container.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 10))
            .opacity(enabled ? 1 : 0.5)
    }
}

extension View {
    func pochiField() -> some View {
        self.padding(.horizontal, 12).padding(.vertical, 10)
            .background(PC.panel, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(PC.border, lineWidth: 1))
            .foregroundStyle(PC.text)
    }
}

/// ブランド表示 (アイコンとアプリ名)。
struct Brand: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "dot.radiowaves.left.and.right").foregroundStyle(Color(hex: 0x60A5FA)).font(.system(size: 20, weight: .bold))
            Text("ぽちっと通話").font(.system(size: 20, weight: .heavy)).foregroundStyle(PC.text)
            Text("α").font(.system(size: 11, weight: .bold)).foregroundStyle(PC.amber)
        }
    }
}

struct Led: View {
    var on: Bool
    var color: Color
    var body: some View {
        Circle().fill(on ? color : PC.ledOff).frame(width: 12, height: 12)
            .shadow(color: on ? color.opacity(0.9) : .clear, radius: 6)
    }
}
