package app.pochitalk.rtc

import app.pochitalk.core.ActionCodec
import app.pochitalk.core.TrysteroCrypto
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.webrtc.MediaStreamTrack
import javax.crypto.spec.SecretKeySpec

/**
 * Trystero 0.20.0 の strategy.js (オファー/アンサーの交換) と room.js (アクション) の移植。
 * 呼び出しはすべて Runtime.scope 上で行うこと。
 */
class TrysteroRoom internal constructor(
    val appId: String,
    val roomId: String,
    private val net: TrysteroNetwork,
) {
    private val selfId = net.selfId
    private val rootTopicPlain = TrysteroCrypto.topicPath("Trystero", appId, roomId)
    private val rootTopic = TrysteroCrypto.sha1Topic(rootTopicPlain)
    private val selfTopic = TrysteroCrypto.sha1Topic(TrysteroCrypto.topicPath(rootTopicPlain, selfId))
    private val key: SecretKeySpec = TrysteroCrypto.genKey("", appId, roomId)

    // ---- strategy 状態
    private val pendingOffers = HashMap<String, HashMap<Int, RtcPeer>>()
    private val connectedPeers = HashMap<String, RtcPeer>()
    private val subIds = ArrayList<Pair<Int, String>>()
    private val announceJobs = ArrayList<Job>()
    private var left = false

    // ---- room 状態
    private val peerMap = HashMap<String, RtcPeer>()
    private val reassemblers = HashMap<String, ActionCodec.Reassembler>()
    private val actions = HashMap<String, Action>()
    private val localTracks = ArrayList<Pair<MediaStreamTrack, String>>()

    var onPeerJoin: ((peerId: String) -> Unit)? = null
    var onPeerLeave: ((peerId: String) -> Unit)? = null

    // 内部アクション (room.js の internalNs)
    private val pingAction = action("@_ping")
    private val pongAction = action("@_pong")
    private val signalAction = action("@_signal")
    private val streamMetaAction = action("@_stream")
    private val trackMetaAction = action("@_track")
    private val leaveAction = action("@_leave")

    init {
        pingAction.onMessage = { _, id -> pongAction.sendString("", id) }
        pongAction.onMessage = { _, _ -> }
        signalAction.onMessage = { msg, id -> if (msg.isJson) peerMap[id]?.signal(msg.json()) }
        streamMetaAction.onMessage = { _, _ -> }
        trackMetaAction.onMessage = { _, _ -> }
        leaveAction.onMessage = { _, id -> exitPeer(id) }
        start()
    }

    // ================================================================ アクション (room.js)

    inner class Action(val type: String) {
        private var nonce = 0
        var onMessage: ((msg: ActionCodec.Message, peerId: String) -> Unit)? = null

        fun sendString(text: String, to: String? = null) = send(ActionCodec.encodeString(type, text, nextNonce()), to)

        fun sendJson(json: JSONObject, to: String? = null) = send(ActionCodec.encodeJson(type, json, nextNonce()), to)

        private fun nextNonce(): Int {
            val n = nonce
            nonce = (nonce + 1) and 0xff
            return n
        }

        private fun send(chunks: List<ByteArray>, to: String?) {
            val targets = if (to != null) listOfNotNull(peerMap[to]) else peerMap.values.toList()
            for (peer in targets) for (chunk in chunks) peer.send(chunk)
        }
    }

    /** アクションを登録する (同じ名前なら同じものを返す)。名前は12バイト以内。 */
    fun action(type: String): Action = actions.getOrPut(type) { Action(type) }

    val peerIds: Set<String> get() = peerMap.keys.toSet()

    /** 指定した相手から受信中の音声レベル (0..1)。 */
    fun fetchInboundAudioLevel(peerId: String, callback: (Double?) -> Unit) {
        val peer = peerMap[peerId]
        if (peer == null) callback(null) else peer.fetchInboundAudioLevel(callback)
    }

    /** 自分の音声トラックを共有する。すでに接続済みの相手と、これから繋がる相手の両方に流す。 */
    fun addLocalTrack(track: MediaStreamTrack, streamId: String) {
        if (localTracks.none { it.first === track }) localTracks.add(track to streamId)
        peerMap.values.forEach { it.addTrack(track, streamId) }
    }

    fun leave() {
        if (left) return
        left = true
        leaveAction.sendString("")
        Runtime.scope.launch {
            delay(99)
            peerMap.values.toList().forEach { it.destroy("leave") }
            peerMap.clear()
            pendingOffers.values.forEach { m -> m.values.forEach { it.destroy("leave") } }
            pendingOffers.clear()
            announceJobs.forEach { it.cancel() }
            subIds.forEach { (i, subId) -> net.unsubscribe(i, subId) }
            subIds.clear()
            net.roomClosed(appId, roomId)
        }
    }

    private fun handleData(id: String, bytes: ByteArray) {
        val re = reassemblers.getOrPut(id) { ActionCodec.Reassembler() }
        val msg = re.feed(bytes) ?: return
        val action = actions[msg.type]
        if (action == null) {
            Runtime.log("unregistered action type: ${msg.type}")
            return
        }
        try {
            action.onMessage?.invoke(msg, id)
        } catch (e: Exception) {
            Runtime.log("action ${msg.type} handler error: ${e.message}")
        }
    }

    private fun exitPeer(id: String) {
        val peer = peerMap.remove(id) ?: return
        reassemblers.remove(id)
        connectedPeers.remove(id)
        peer.destroy("exit")
        onPeerLeave?.invoke(id)
    }

    /** strategy が接続を確立したピアを room に渡す (room.js の onPeer)。 */
    private fun onPeerConnect(peer: RtcPeer, id: String) {
        if (peerMap.containsKey(id)) return
        peerMap[id] = peer
        peer.onSignal { json -> signalAction.sendJson(json, id) } // 接続後の SDP は全てデータチャネルで送る
        peer.onClose { if (peerMap[id] === peer) exitPeer(id) }
        peer.setDataHandler { bytes -> handleData(id, bytes) }
        onPeerJoin?.invoke(id)
        localTracks.forEach { (track, streamId) -> peer.addTrack(track, streamId) }
    }

    // ================================================================ strategy.js

    private fun start() {
        for (i in net.relays.indices) {
            val rootSub = net.subscribe(this, i, rootTopic)
            val selfSub = net.subscribe(this, i, selfTopic)
            subIds.add(i to rootSub)
            subIds.add(i to selfSub)
            announceJobs.add(
                Runtime.scope.launch {
                    while (isActive && !left) {
                        net.relays[i].publish(rootTopic, JSONObject().put("peerId", selfId).toString())
                        delay(ANNOUNCE_INTERVAL_MS)
                    }
                },
            )
        }
    }

    private fun makeIceServers() = net.iceServers

    private fun newPeer(initiator: Boolean) = RtcPeer(net.factory, makeIceServers(), initiator)

    private fun encryptSignal(signal: JSONObject): JSONObject =
        JSONObject().put("type", signal.getString("type"))
            .put("sdp", TrysteroCrypto.encrypt(key, signal.getString("sdp")))

    private fun connectPeer(peer: RtcPeer, peerId: String, clientId: Int) {
        val existing = connectedPeers[peerId]
        if (existing != null) {
            if (existing !== peer) peer.destroy("duplicate")
            return
        }
        connectedPeers[peerId] = peer
        onPeerConnect(peer, peerId)
        pendingOffers[peerId]?.forEach { (i, p) -> if (i != clientId) p.destroy("other offer") }
        pendingOffers.remove(peerId)
    }

    private fun disconnectPeer(peer: RtcPeer, peerId: String) {
        if (connectedPeers[peerId] === peer) connectedPeers.remove(peerId)
    }

    private fun prunePendingOffer(peerId: String, clientId: Int) {
        if (connectedPeers.containsKey(peerId)) return
        val offer = pendingOffers[peerId]?.remove(clientId) ?: return
        offer.destroy("offer expired")
    }

    /** リレーから届いたメッセージ (announce / offer / answer) を処理する。 */
    internal fun handleRelayMessage(clientId: Int, topic: String, raw: String) {
        if (left) return
        if (topic != rootTopic && topic != selfTopic) return
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        val peerId = msg.optString("peerId", "")
        val offer = msg.optJSONObject("offer")
        val answer = msg.optJSONObject("answer")
        if (peerId.isEmpty() || peerId == selfId || connectedPeers.containsKey(peerId)) return

        val client = net.relays[clientId]
        val replyTopic = TrysteroCrypto.sha1Topic(TrysteroCrypto.topicPath(rootTopicPlain, peerId))

        if (offer == null && answer == null) {
            // ---- 相手の announce: こちらからオファーを送る
            if (pendingOffers[peerId]?.containsKey(clientId) == true) return
            if ((pendingOffers[peerId]?.size ?: 0) >= MAX_PENDING_OFFERS_PER_PEER) return
            val peer = newPeer(initiator = true)
            pendingOffers.getOrPut(peerId) { HashMap() }[clientId] = peer
            peer.onConnect { connectPeer(peer, peerId, clientId) }
            peer.onClose { disconnectPeer(peer, peerId) }
            Runtime.scope.launch {
                // ブラウザ版は事前に作ったオファープールを使うが、こちらは都合のよいときに作る
                val signal = withTimeoutOrNull(OFFER_BUILD_TIMEOUT_MS) { peer.firstSignal.await() }
                if (signal == null || peer.isDead || left) {
                    pendingOffers[peerId]?.remove(clientId)
                    peer.destroy("offer not ready")
                    return@launch
                }
                launch {
                    delay((ANNOUNCE_INTERVAL_MS * 0.9).toLong())
                    prunePendingOffer(peerId, clientId)
                }
                client.publish(
                    replyTopic,
                    JSONObject().put("peerId", selfId).put("offer", encryptSignal(signal)).toString(),
                )
            }
        } else if (offer != null) {
            // ---- 相手からのオファー: アンサーを返す
            val myOffer = pendingOffers[peerId]?.get(clientId)
            // 同時にオファーを出し合ったら、selfId が大きい側が自分のオファーを優先する
            if (myOffer != null && selfId > peerId) return
            val plainSdp = try {
                TrysteroCrypto.decrypt(key, offer.getString("sdp"))
            } catch (e: Exception) {
                Runtime.log("offer decrypt failed (room id / app id mismatch?): ${e.message}")
                return
            }
            val peer = newPeer(initiator = false)
            peer.onConnect { connectPeer(peer, peerId, clientId) }
            peer.onClose { disconnectPeer(peer, peerId) }
            peer.signal(JSONObject().put("type", offer.getString("type")).put("sdp", plainSdp))
            Runtime.scope.launch {
                // 接続できなかったアンサー側ピアが残り続けないようにする
                delay(UNCONNECTED_PEER_TTL_MS)
                if (connectedPeers[peerId] !== peer) peer.destroy("never connected")
            }
            Runtime.scope.launch {
                val signal = withTimeoutOrNull(OFFER_BUILD_TIMEOUT_MS) { peer.firstSignal.await() }
                if (signal == null || peer.isDead || left) {
                    peer.destroy("answer not ready")
                    return@launch
                }
                client.publish(
                    replyTopic,
                    JSONObject().put("peerId", selfId).put("answer", encryptSignal(signal)).toString(),
                )
            }
        } else if (answer != null) {
            // ---- 相手からのアンサー
            val plainSdp = try {
                TrysteroCrypto.decrypt(key, answer.getString("sdp"))
            } catch (e: Exception) {
                Runtime.log("answer decrypt failed: ${e.message}")
                return
            }
            val peer = pendingOffers[peerId]?.get(clientId)
            if (peer != null && !peer.isDead) {
                peer.signal(JSONObject().put("type", answer.getString("type")).put("sdp", plainSdp))
            }
        }
    }

    companion object {
        private const val ANNOUNCE_INTERVAL_MS = 5_333L
        private const val OFFER_BUILD_TIMEOUT_MS = 15_000L
        private const val MAX_PENDING_OFFERS_PER_PEER = 2
        private const val UNCONNECTED_PEER_TTL_MS = 30_000L
    }
}
