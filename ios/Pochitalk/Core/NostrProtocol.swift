import CryptoKit
import Foundation
import P256K

/// Trystero 0.25.4 の nostr 戦略が使うイベントの作り方 (Android 版 NostrProtocol.kt の移植)。
///  - kind は topic ごとに変わる: strToNum(topic, 10000) + 20000。タグは ["x", topic]
///  - 在室通知 (部屋一覧) は Web 版の独自方式: kind 29334, タグ x = "pochitalk-presence-v1"
enum NostrProtocol {
    static let tag = "x"
    static let presenceKind = 29334
    static let presenceTopic = "pochitalk-presence-v1"

    /// 起動ごとに作る使い捨ての署名鍵 (端末には保存しない)。
    private static let privateKey: P256K.Schnorr.PrivateKey = try! P256K.Schnorr.PrivateKey()
    static let publicKeyHex: String = Data(privateKey.xonly.bytes).hex

    /// JS の strToNum: UTF-16 の文字コードの合計を limit で割った余り。
    static func strToNum(_ s: String, _ limit: Int) -> Int {
        var sum = 0
        for u in s.utf16 { sum += Int(u) }
        return sum % limit
    }

    static func topicToKind(_ topic: String) -> Int { strToNum(topic, 10_000) + 20_000 }

    /// JSON.stringify と同じ文字列エスケープ (イベント id のハッシュ対象なので、リレー側の再計算と一致させる)。
    static func jsonString(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        return out + "\""
    }

    static func createEvent(kind: Int, topic: String, content: String, nowSec: Int = Int(Date().timeIntervalSince1970)) -> String {
        let pubkey = publicKeyHex
        let tags = "[[" + jsonString(tag) + "," + jsonString(topic) + "]]"
        let serialized = "[0," + jsonString(pubkey) + ",\(nowSec),\(kind)," + tags + "," + jsonString(content) + "]"
        let digest = SHA256.hash(data: Data(serialized.utf8))
        let id = Data(digest).hex
        let sig = (try? privateKey.signature(for: digest)).map { Data($0.dataRepresentation).hex } ?? ""
        return "[\"EVENT\",{\"kind\":\(kind),\"content\":" + jsonString(content) + ",\"pubkey\":\"\(pubkey)\",\"created_at\":\(nowSec),\"tags\":" + tags + ",\"id\":\"\(id)\",\"sig\":\"\(sig)\"}]"
    }

    static func createTopicEvent(topic: String, content: String) -> String {
        createEvent(kind: topicToKind(topic), topic: topic, content: content)
    }

    /// 複数の topic をまとめて購読する (Trystero の batch 購読と同じ形)。
    static func subscribe(subId: String, topics: [String], since: Int = Int(Date().timeIntervalSince1970)) -> String {
        var kinds: [Int] = []
        for t in topics { let k = topicToKind(t); if !kinds.contains(k) { kinds.append(k) } }
        let xs = topics.map { jsonString($0) }.joined(separator: ",")
        return "[\"REQ\"," + jsonString(subId) + ",{\"kinds\":[" + kinds.map(String.init).joined(separator: ",") + "],\"since\":\(since),\"#x\":[" + xs + "]}]"
    }

    /// 在室通知の購読 (Web 版と同じ: 5秒前から)。
    static func subscribePresence(subId: String, now: Int = Int(Date().timeIntervalSince1970)) -> String {
        "[\"REQ\"," + jsonString(subId) + ",{\"kinds\":[\(presenceKind)],\"#x\":[" + jsonString(presenceTopic) + "],\"since\":\(now - 5)}]"
    }

    static func unsubscribe(_ subId: String) -> String { "[\"CLOSE\"," + jsonString(subId) + "]" }
}
