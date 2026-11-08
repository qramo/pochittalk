import Foundation

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }

    init?(hex: String) {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        func nib(_ c: UInt8) -> UInt8? {
            switch c {
            case 48...57: return c - 48
            case 65...70: return c - 55
            case 97...102: return c - 87
            default: return nil
            }
        }
        var i = 0
        while i < chars.count {
            guard let a = nib(chars[i]), let b = nib(chars[i + 1]) else { return nil }
            out.append(a << 4 | b)
            i += 2
        }
        self = out
    }
}

/// JSON の読み書きの小さな道具 (Android の JSONObject 相当)。
enum JSON {
    static func object(_ text: String) -> [String: Any]? {
        guard let d = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }

    static func string(_ obj: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: obj, options: [.withoutEscapingSlashes]) else { return "{}" }
        return String(decoding: d, as: UTF8.self)
    }
}

/// SDP の判定。SDP の改行は CRLF だが、Swift の String は CRLF を1文字として扱うため、
/// "\nm=" の検索や components(separatedBy: "\n") は当てにならない。CharacterSet (Unicode スカラー単位) で行に分ける。
enum Sdp {
    static func lines(_ sdp: String) -> [String] {
        sdp.components(separatedBy: .newlines).filter { !$0.isEmpty }
    }

    /// m= 行 (メディアの節) の数。
    static func mediaSectionCount(_ sdp: String) -> Int { lines(sdp).filter { $0.hasPrefix("m=") }.count }
}
