import Foundation
import WebRTC

/// Trystero 0.25.4 nostr 戦略の「リレー接続」部分 (Android 版 TrysteroNetwork.kt の移植)。
///  - リレーは呼び出し側が選ぶ (Web 版と同じ: 動いているものから最大3本)。
///  - selfId はプロセスで1つ (全部屋共通)。
///  - 1つのリレー接続を全部屋で共有する。
final class TrysteroNetwork {
    let factory: RTCPeerConnectionFactory
    /// 接続の準備を作るたびに呼ばれる。TURN の資格情報が更新されても、次の接続から反映される。
    let iceServers: () -> [RTCIceServer]

    /// Trystero の selfId (20文字)。先頭を "00" にして、相手より「小さい id」になるようにしている。
    /// Trystero は id の小さい側がオファーを作るので、アプリが必ずオファー側になる。Web 版 (0.25.4) は、
    /// 57秒以上前に作り置きしたオファーを出すとき、ブラウザの挙動 (ロールバック後の ICE 再起動) で
    /// 中身の無い SDP になり、ブラウザ側がオファーを出す役のときはつながらないことがあるため。
    static let selfId: String = "00" + TrysteroCrypto.genId(18)
    var selfId: String { Self.selfId }

    struct SubInfo { weak var room: TrysteroRoom?; let clientIndex: Int }
    private var subs: [String: SubInfo] = [:]
    private var rooms: [String: TrysteroRoom] = [:]
    private(set) var relays: [NostrClient] = []

    init(factory: RTCPeerConnectionFactory, iceServers: @escaping () -> [RTCIceServer], relayUrls: [String]) {
        self.factory = factory
        self.iceServers = iceServers
        relays = relayUrls.enumerated().map { (i, url) in
            NostrClient(index: i, url: url) { [weak self] client, subId, topic, content, _ in
                guard let self, let info = self.subs[subId], info.clientIndex == client.index else { return }
                info.room?.handleRelayMessage(relay: info.clientIndex, topic: topic, raw: content)
            }
        }
    }

    func start() { relays.forEach { $0.connect() } }

    func reconnectRelays() { relays.forEach { $0.reconnect() } }
    func refreshRelays() { relays.forEach { $0.refresh() } }

    func close() {
        rooms.values.forEach { $0.leave() }
        relays.forEach { $0.close() }
        subs.removeAll()
    }

    func joinRoom(appId: String, roomId: String) -> TrysteroRoom {
        let key = "\(appId)/\(roomId)"
        if let r = rooms[key] { return r }
        let room = TrysteroRoom(appId: appId, roomId: roomId, net: self)
        rooms[key] = room
        room.start()
        return room
    }

    func roomClosed(appId: String, roomId: String, room: TrysteroRoom) {
        let key = "\(appId)/\(roomId)"
        if rooms[key] === room { rooms.removeValue(forKey: key) }
    }

    func subscribe(room: TrysteroRoom, clientIndex: Int, topics: [String]) -> String {
        let subId = TrysteroCrypto.genId(64)
        subs[subId] = SubInfo(room: room, clientIndex: clientIndex)
        relays[clientIndex].subscribe(subId: subId) { NostrProtocol.subscribe(subId: subId, topics: topics) }
        return subId
    }

    func unsubscribe(clientIndex: Int, subId: String) {
        subs.removeValue(forKey: subId)
        relays[clientIndex].unsubscribe(subId: subId)
    }
}
