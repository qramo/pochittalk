import Foundation

/// Trystero 0.25.4 (action-wire.ts) のデータチャネル上のフレーミング (Android 版 ActionCodec.kt の移植)。
///
///  byte 0..31 : アクション名 (UTF-8, 0 埋めで32バイト固定)
///  byte 32..33: nonce (16ビット, ビッグエンディアン)
///  byte 34    : tag  bit0=最後のチャンク bit1=メタ bit2=バイナリ bit3=JSON
///  byte 35    : progress (0..255)
///  byte 36..  : ペイロード (1チャンクの最大長は 16KiB - 36)
///
/// 共有ピア (shared-peer.ts) が部屋ごとのトークンで包む:
///  部屋フレーム: [1][トークン長 2バイト][トークン UTF-8][アクションのフレーム]
///  在室フレーム: [2][在室=1/退室=0][トークン長 2バイト][トークン UTF-8]
enum ActionCodec {
    static let typeByteLimit = 32
    static let payloadIndex = 36
    static let chunkSize = 16 * 1024 - payloadIndex
    private static let tagLast: UInt8 = 1, tagMeta: UInt8 = 2, tagBinary: UInt8 = 4, tagJSON: UInt8 = 8

    static func encode(type: String, data: Data, nonce: Int, isBinary: Bool, isJson: Bool) -> [Data] {
        let typeBytes = Array(type.utf8)
        precondition(typeBytes.count <= typeByteLimit, "action type too long")
        let total = max(1, (data.count + chunkSize - 1) / chunkSize)
        return (0..<total).map { i in
            let isLast = i == total - 1
            let from = i * chunkSize
            let to = min(data.count, from + chunkSize)
            var chunk = Data(count: payloadIndex)
            for (k, b) in typeBytes.enumerated() { chunk[k] = b }
            chunk[32] = UInt8((nonce >> 8) & 0xff)
            chunk[33] = UInt8(nonce & 0xff)
            var tag: UInt8 = 0
            if isLast { tag |= tagLast }
            if isBinary { tag |= tagBinary }
            if isJson { tag |= tagJSON }
            chunk[34] = tag
            chunk[35] = UInt8((Double(i + 1) / Double(total) * 255).rounded())
            chunk.append(data[(data.startIndex + from)..<(data.startIndex + to)])
            return chunk
        }
    }

    static func encodeString(type: String, text: String, nonce: Int) -> [Data] {
        encode(type: type, data: Data(text.utf8), nonce: nonce, isBinary: false, isJson: false)
    }

    static func encodeJson(type: String, json: [String: Any], nonce: Int) -> [Data] {
        encode(type: type, data: Data(JSON.string(json).utf8), nonce: nonce, isBinary: false, isJson: true)
    }

    /// 先頭32バイトからアクション名を読む (未登録の名前を、組み立てる前に捨てるため)。
    static func peekType(_ data: Data) -> String? {
        guard data.count >= payloadIndex else { return nil }
        let raw = data[data.startIndex..<(data.startIndex + typeByteLimit)].filter { $0 != 0 }
        return String(decoding: raw, as: UTF8.self)
    }

    struct Message {
        let type: String
        let isBinary: Bool
        let isJson: Bool
        let payload: Data
        var text: String { String(decoding: payload, as: UTF8.self) }
        func json() -> [String: Any] { JSON.object(text) ?? [:] }
    }

    /// ピアごとに1つ持ち、受信チャンクを組み立てる。
    final class Reassembler {
        private var pending: [String: [Data]] = [:]

        func feed(_ data: Data) -> Message? {
            guard let type = peekType(data) else { return nil }
            let s = data.startIndex
            let nonce = (Int(data[s + 32]) << 8) | Int(data[s + 33])
            let tag = data[s + 34]
            let payload = Data(data[(s + payloadIndex)...])
            let key = "\(type)/\(nonce)"
            if tag & tagMeta == 0 { pending[key, default: []].append(payload) }
            guard tag & tagLast != 0 else { return nil }
            let full = (pending.removeValue(forKey: key) ?? []).reduce(into: Data()) { $0.append($1) }
            return Message(type: type, isBinary: tag & tagBinary != 0, isJson: tag & tagJSON != 0, payload: full)
        }
    }

    // ---- 共有ピアの部屋フレーム

    static func wrapRoomFrame(token: String, data: Data) -> Data {
        let t = Data(token.utf8)
        var f = Data([1, UInt8(t.count >> 8), UInt8(t.count & 0xff)])
        f.append(t)
        f.append(data)
        return f
    }

    static func wrapPresenceFrame(token: String, isPresent: Bool) -> Data {
        let t = Data(token.utf8)
        var f = Data([2, isPresent ? 1 : 0, UInt8(t.count >> 8), UInt8(t.count & 0xff)])
        f.append(t)
        return f
    }

    enum Frame {
        case room(token: String, payload: Data)
        case presence(token: String, isPresent: Bool)
    }

    static func unwrapFrame(_ d: Data) -> Frame? {
        let b = [UInt8](d)
        guard b.count >= 3 else { return nil }
        if b[0] == 1 {
            let size = (Int(b[1]) << 8) | Int(b[2])
            let header = 3 + size
            guard size > 0, b.count >= header else { return nil }
            return .room(token: String(decoding: b[3..<header], as: UTF8.self), payload: Data(b[header...]))
        }
        guard b[0] == 2, b.count >= 4 else { return nil }
        let size = (Int(b[2]) << 8) | Int(b[3])
        let header = 4 + size
        guard size > 0, b.count >= header else { return nil }
        return .presence(token: String(decoding: b[4..<header], as: UTF8.self), isPresent: b[1] == 1)
    }
}
