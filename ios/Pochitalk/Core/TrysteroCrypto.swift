import CryptoKit
import Foundation

/// Trystero 0.25.4 と同じ表現を作る (Android 版 TrysteroCrypto.kt の移植)。
///  - topicPath = parts.join("@")
///  - sha1(str) = 各バイトを toString(36) して連結 (ゼロ埋め無し。16進ではない)
///  - 鍵 = SHA-256("${password}:${appId}:${roomId}") をそのまま AES-GCM の鍵に使う
///  - 暗号文 = iv(16バイト)を10進で "," 連結 + "$" + base64(暗号文||GCMタグ)
enum TrysteroCrypto {
    static func topicPath(_ parts: String...) -> String { parts.joined(separator: "@") }

    static func sha1Topic(_ str: String) -> String {
        Insecure.SHA1.hash(data: Data(str.utf8)).map { String($0, radix: 36) }.joined()
    }

    /// 共有ピア上で部屋を見分けるトークン = hex(SHA-256("Trystero:appId:roomId"))
    static func deriveRoomNamespace(appId: String, roomId: String) -> String {
        Data(SHA256.hash(data: Data("Trystero:\(appId):\(roomId)".utf8))).hex
    }

    static func genKey(secret: String, appId: String, roomId: String) -> SymmetricKey {
        SymmetricKey(data: SHA256.hash(data: Data("\(secret):\(appId):\(roomId)".utf8)))
    }

    static func encrypt(_ key: SymmetricKey, _ plaintext: String) -> String {
        var iv = Data(count: 16)
        iv.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        let nonce = try! AES.GCM.Nonce(data: iv)
        let box = try! AES.GCM.seal(Data(plaintext.utf8), using: key, nonce: nonce)
        let ivText = iv.map { String($0) }.joined(separator: ",")
        return ivText + "$" + (box.ciphertext + box.tag).base64EncodedString()
    }

    static func decrypt(_ key: SymmetricKey, _ raw: String) throws -> String {
        guard let sep = raw.firstIndex(of: "$") else { throw CryptoError.bad }
        let ivParts = raw[raw.startIndex..<sep].split(separator: ",").compactMap { UInt8($0) }
        guard let data = Data(base64Encoded: String(raw[raw.index(after: sep)...])), data.count >= 16, !ivParts.isEmpty else { throw CryptoError.bad }
        let nonce = try AES.GCM.Nonce(data: Data(ivParts))
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: data.dropLast(16), tag: data.suffix(16))
        let plain = try AES.GCM.open(box, using: key)
        guard let s = String(data: plain, encoding: .utf8) else { throw CryptoError.bad }
        return s
    }

    enum CryptoError: Error { case bad }

    private static let idChars = Array("0123456789AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz")

    /// Trystero の genId。selfId は20文字、REQ の subId は64文字。
    static func genId(_ n: Int) -> String {
        String((0..<n).map { _ in idChars[Int.random(in: 0..<idChars.count)] })
    }
}
