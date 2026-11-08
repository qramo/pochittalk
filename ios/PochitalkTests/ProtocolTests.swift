import CryptoKit
import P256K
import XCTest
@testable import Pochitalk

/// Web 版 (Trystero 0.25.4) と通話するための土台になる、署名・暗号・フレーミングの単体テスト。
/// 期待値は Android 版 ProtocolTest.kt と同じ (本物の Trystero 0.25.4 のコードと @noble/curves で生成したもの)。
final class ProtocolTests: XCTestCase {
    func testTopicsAndKinds() {
        let root = TrysteroCrypto.topicPath("Trystero", "pochitalk-v2", "h-7a0b07c1")
        XCTAssertEqual(root, "Trystero@pochitalk-v2@h-7a0b07c1")
        XCTAssertEqual(TrysteroCrypto.sha1Topic(root), "2fj6409019131v2l4a255q441867255a4x1s")
        XCTAssertEqual(TrysteroCrypto.sha1Topic(TrysteroCrypto.topicPath(root, "AbCdEfGhIjKlMnOpQrSt")), "72426o4g76r03e4m44us735g6t1m2t4z4o1n")
        XCTAssertEqual(NostrProtocol.topicToKind("2fj6409019131v2l4a255q441867255a4x1s"), 22373)
        XCTAssertEqual(NostrProtocol.topicToKind(NostrProtocol.presenceTopic), 22069)
    }

    func testRoomNamespaceToken() {
        XCTAssertEqual(TrysteroCrypto.deriveRoomNamespace(appId: "pochitalk-v2", roomId: "h-7a0b07c1"),
                       "e5e3690babf1797ba99123b0b32a75ab6fa48a4b98470acee62c49b04b9f147f")
    }

    func testDecryptsCiphertextMadeByTrystero() throws {
        let key = TrysteroCrypto.genKey(secret: "", appId: "pochitalk-v2", roomId: "h-7a0b07c1")
        let plain = try TrysteroCrypto.decrypt(key, "162,123,96,178,53,97,138,97,125,182,218,77,66,89,123,104$dNATjcwjbQenyKCj0DxMUbsaDF5bmvHhBcQ4UQ==")
        XCTAssertEqual(plain, "v=0 test sdp")
    }

    func testEncryptDecryptRoundTrip() throws {
        let key = TrysteroCrypto.genKey(secret: "", appId: "a", roomId: "b")
        XCTAssertEqual(try TrysteroCrypto.decrypt(key, TrysteroCrypto.encrypt(key, "hello 日本語")), "hello 日本語")
    }

    func testFramingMatchesActionWire() {
        let stateTrue = "7074745374617465000000000000000000000000000000000000000000000000000009ff7b22697354616c6b696e67223a747275657d"
        let name = "7074744e616d6500000000000000000000000000000000000000000000000000000001ffe381bde381a1f09f9982"
        let ready = "405f687372656164790000000000000000000000000000000000000000000000000001ff"
        XCTAssertEqual(ActionCodec.encodeJson(type: "pttState", json: ["isTalking": true], nonce: 0)[0].hex, stateTrue)
        XCTAssertEqual(ActionCodec.encodeString(type: "pttName", text: "ぽち🙂", nonce: 0)[0].hex, name)
        XCTAssertEqual(ActionCodec.encodeString(type: "@_hsready", text: "", nonce: 0)[0].hex, ready)
        let re = ActionCodec.Reassembler()
        let m = re.feed(Data(hex: stateTrue)!)
        XCTAssertEqual(m?.type, "pttState")
        XCTAssertEqual(m?.isJson, true)
        XCTAssertEqual(m?.json()["isTalking"] as? Bool, true)
        XCTAssertEqual(re.feed(Data(hex: name)!)?.text, "ぽち🙂")
        XCTAssertEqual(ActionCodec.peekType(Data(hex: ready)!), "@_hsready")
    }

    func testMultiChunk() {
        let big = String(repeating: "x", count: 40_000)
        let chunks = ActionCodec.encodeString(type: "big", text: big, nonce: 0)
        XCTAssertEqual(chunks.map { $0.count }, [16384, 16384, 7340])
        XCTAssertEqual(chunks.map { Int($0[34]) }, [0, 0, 1])
        XCTAssertEqual(chunks.map { Int($0[35]) }, [85, 170, 255])
        let re = ActionCodec.Reassembler()
        var got: ActionCodec.Message?
        for c in chunks { got = re.feed(c) ?? got }
        XCTAssertEqual(got?.text, big)
    }

    func testRoomFrames() {
        let token = "e5e3690babf1797ba99123b0b32a75ab6fa48a4b98470acee62c49b04b9f147f"
        let room = "01004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766010203"
        XCTAssertEqual(ActionCodec.wrapRoomFrame(token: token, data: Data([1, 2, 3])).hex, room)
        XCTAssertEqual(ActionCodec.wrapPresenceFrame(token: token, isPresent: true).hex,
                       "0201004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766")
        guard case .room(let t, let p)? = ActionCodec.unwrapFrame(Data(hex: room)!) else { return XCTFail() }
        XCTAssertEqual(t, token)
        XCTAssertEqual(p.hex, "010203")
        XCTAssertNil(ActionCodec.unwrapFrame(Data([1, 0])))
    }

    func testNostrEventIsSignedAndHashedLikeJsonStringify() throws {
        let ev = NostrProtocol.createEvent(kind: 22373, topic: "topicXYZ", content: "{\"a\":\"q\\\"\"}\n日本語", nowSec: 1_700_000_000)
        let arr = try JSONSerialization.jsonObject(with: Data(ev.utf8)) as! [Any]
        let body = arr[1] as! [String: Any]
        let tags = "[[\"x\",\"topicXYZ\"]]"
        let serialized = "[0,\(NostrProtocol.jsonString(body["pubkey"] as! String)),1700000000,22373,\(tags),\(NostrProtocol.jsonString(body["content"] as! String))]"
        let id = Data(SHA256.hash(data: Data(serialized.utf8))).hex
        XCTAssertEqual(id, body["id"] as? String)
        // 署名が正しいこと (BIP340 検証)
        let pub = try P256K.Schnorr.XonlyKey(dataRepresentation: Data(hex: body["pubkey"] as! String)!, keyParity: 0)
        let sig = try P256K.Schnorr.SchnorrSignature(dataRepresentation: Data(hex: body["sig"] as! String)!)
        XCTAssertTrue(pub.isValidSignature(sig, for: SHA256.hash(data: Data(serialized.utf8))))
    }

    func testSubscribeRequest() throws {
        let req = try JSONSerialization.jsonObject(with: Data(NostrProtocol.subscribe(subId: "sub1", topics: ["aaa", "bbb"], since: 1_700_000_000).utf8)) as! [Any]
        XCTAssertEqual(req[0] as? String, "REQ")
        let f = req[2] as! [String: Any]
        XCTAssertEqual(f["since"] as? Int, 1_700_000_000)
        XCTAssertEqual((f["#x"] as? [String])?.count, 2)
        let pres = try JSONSerialization.jsonObject(with: Data(NostrProtocol.subscribePresence(subId: "p", now: 1_700_000_000).utf8)) as! [Any]
        XCTAssertEqual((pres[2] as! [String: Any])["since"] as? Int, 1_699_999_995)
    }
}
