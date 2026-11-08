import CoreImage.CIFilterBuiltins
import SwiftUI

/// 無線機の裏面: 招待の QR コード、ルームハッシュ、URL。
struct QrBackFace: View {
    let roomKey: String
    let onBack: () -> Void

    var body: some View {
        let url = PochiEngine.shared.inviteUrl(for: roomKey) ?? PochiEngine.inviteBaseUrl + "?r=" + roomKey
        VStack(spacing: 14) {
            HStack {
                Text("招待 QR").font(.system(size: 16, weight: .bold)).foregroundStyle(Color(hex: 0x93C5FD))
                Spacer()
                Button("もどる", action: onBack).buttonStyle(SubButtonStyle(small: true))
            }
            Group {
                if let img = Self.qrImage(url) {
                    Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                } else {
                    Text("QRコードを作れませんでした。招待URLの送信をお使いください。").font(.system(size: 13)).foregroundStyle(Color(hex: 0x374151))
                }
            }
            .padding(22)
            .frame(maxWidth: 270)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
            .rotationEffect(.degrees(-1))
            .shadow(radius: 6)
            Text(formatKey(roomKey)).font(.system(size: 22, weight: .heavy, design: .monospaced)).tracking(2).foregroundStyle(PC.sky)
            Text("このコードを読み取ると、同じ部屋に入れます。").font(.system(size: 13)).foregroundStyle(PC.muted).multilineTextAlignment(.center)
            Text(url).font(.system(size: 10)).foregroundStyle(PC.muted.opacity(0.75)).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
    }

    /// 白地に黒の QR (周りの余白つき)。
    static func qrImage(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scaled = out.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
