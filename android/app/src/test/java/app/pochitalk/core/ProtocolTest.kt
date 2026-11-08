package app.pochitalk.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web版 (Trystero 0.25.4) と通話するための土台になる、署名・暗号・フレーミングの単体テスト。
 * 期待値は、本物の Trystero 0.25.4 のコード (crypto / action-wire / shared-peer) と @noble/curves で生成したもの。
 */
class ProtocolTest {

    // BIP340 公式テストベクタ (https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv) の 0〜2
    private val bip340 = listOf(
        arrayOf(
            "0000000000000000000000000000000000000000000000000000000000000003",
            "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0000000000000000000000000000000000000000000000000000000000000000",
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
        ),
        arrayOf(
            "B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
            "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
            "0000000000000000000000000000000000000000000000000000000000000001",
            "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
            "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
        ),
        arrayOf(
            "C90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B14E5C9",
            "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
            "C87AA53824B4D7AE2EB035A2B5BBBCCC080E76CDC6D1692C4B0B62D798E6D906",
            "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
            "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1BAB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
        ),
    )

    @Test
    fun schnorrMatchesBip340Vectors() {
        for (v in bip340) {
            val sk = v[0].hexToBytes()
            assertEquals(v[1].lowercase(), Schnorr.publicKey(sk).toHex())
            val sig = Schnorr.sign(v[3].hexToBytes(), sk, v[2].hexToBytes())
            assertEquals(v[4].lowercase(), sig.toHex())
            assertTrue(Schnorr.verify(sig, v[3].hexToBytes(), v[1].hexToBytes()))
        }
    }

    @Test
    fun topicsAndKindsMatchTrystero0254() {
        val root = TrysteroCrypto.topicPath("Trystero", "pochitalk-v2", "h-7a0b07c1")
        assertEquals("Trystero@pochitalk-v2@h-7a0b07c1", root)
        assertEquals("2fj6409019131v2l4a255q441867255a4x1s", TrysteroCrypto.sha1Topic(root))
        assertEquals("72426o4g76r03e4m44us735g6t1m2t4z4o1n", TrysteroCrypto.sha1Topic(TrysteroCrypto.topicPath(root, "AbCdEfGhIjKlMnOpQrSt")))
        // イベントの kind は topic ごとに変わる: strToNum(topic, 10000) + 20000
        assertEquals(22373, NostrProtocol.topicToKind("2fj6409019131v2l4a255q441867255a4x1s"))
        assertEquals(22069, NostrProtocol.topicToKind(NostrProtocol.PRESENCE_TOPIC))
        assertEquals(29334, NostrProtocol.PRESENCE_KIND)
    }

    @Test
    fun roomNamespaceTokenMatchesTrystero0254() {
        assertEquals("e5e3690babf1797ba99123b0b32a75ab6fa48a4b98470acee62c49b04b9f147f", TrysteroCrypto.deriveRoomNamespace("pochitalk-v2", "h-7a0b07c1"))
    }

    @Test
    fun decryptsCiphertextMadeByTrystero0254() {
        val key = TrysteroCrypto.genKey("", "pochitalk-v2", "h-7a0b07c1")
        assertEquals("v=0 test sdp", TrysteroCrypto.decrypt(key, "162,123,96,178,53,97,138,97,125,182,218,77,66,89,123,104\$dNATjcwjbQenyKCj0DxMUbsaDF5bmvHhBcQ4UQ=="))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val key = TrysteroCrypto.genKey("", "a", "b")
        assertEquals("hello", TrysteroCrypto.decrypt(key, TrysteroCrypto.encrypt(key, "hello")))
    }

    @Test
    fun framingMatchesTrystero0254ActionWire() {
        // action-wire.ts が実際に送ったバイト列 (名前32バイト + nonce2バイト + tag + progress + 本文)
        val stateTrue = "7074745374617465000000000000000000000000000000000000000000000000000009ff7b22697354616c6b696e67223a747275657d"
        val stateFalse = "7074745374617465000000000000000000000000000000000000000000000000000109ff7b22697354616c6b696e67223a66616c73657d"
        val name = "7074744e616d6500000000000000000000000000000000000000000000000000000001ffe381bde381a1f09f9982"
        val ready = "405f687372656164790000000000000000000000000000000000000000000000000001ff"
        assertEquals(stateTrue, ActionCodec.encodeJson("pttState", JSONObject().put("isTalking", true), 0)[0].toHex())
        assertEquals(stateFalse, ActionCodec.encodeJson("pttState", JSONObject().put("isTalking", false), 1)[0].toHex())
        assertEquals(name, ActionCodec.encodeString("pttName", "ぽち🙂", 0)[0].toHex())
        assertEquals(ready, ActionCodec.encodeString("@_hsready", "", 0)[0].toHex())

        val re = ActionCodec.Reassembler()
        val m = re.feed(stateTrue.hexToBytes())
        assertNotNull(m)
        assertEquals("pttState", m!!.type)
        assertTrue(m.isJson)
        assertTrue(m.json().getBoolean("isTalking"))
        assertEquals("ぽち🙂", re.feed(name.hexToBytes())!!.text)
        assertEquals("@_hsready", ActionCodec.peekType(ready.hexToBytes()))
        assertEquals("", re.feed(ready.hexToBytes())!!.text)
    }

    @Test
    fun multiChunkMatchesTrystero0254AndRoundTrips() {
        val big = "x".repeat(40_000)
        val chunks = ActionCodec.encodeString("big", big, 0)
        // 本物は 16384 + 16384 + 7340 バイト、progress は 85 / 170 / 255、tag は最後だけ 1
        assertEquals(listOf(16384, 16384, 7340), chunks.map { it.size })
        assertEquals(listOf(0, 0, 1), chunks.map { it[34].toInt() })
        assertEquals(listOf(85, 170, 255), chunks.map { it[35].toInt() and 0xff })
        val re = ActionCodec.Reassembler()
        var got: ActionCodec.Message? = null
        chunks.forEach { got = re.feed(it) ?: got }
        assertEquals(big, got?.text)
    }

    @Test
    fun nonceIsSixteenBits() {
        val c = ActionCodec.encodeString("x", "a", 0x1234)[0]
        assertEquals(0x12, c[32].toInt())
        assertEquals(0x34, c[33].toInt())
    }

    @Test
    fun roomFramesMatchTrystero0254SharedPeer() {
        val token = "e5e3690babf1797ba99123b0b32a75ab6fa48a4b98470acee62c49b04b9f147f"
        // 部屋フレーム: [1][長さ2バイト][トークン][本文]、在室フレーム: [2][在室/退室][長さ2バイト][トークン]
        assertEquals("01004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766010203", ActionCodec.wrapRoomFrame(token, byteArrayOf(1, 2, 3)).toHex())
        assertEquals("0201004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766", ActionCodec.wrapPresenceFrame(token, true).toHex())
        assertEquals("0200004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766", ActionCodec.wrapPresenceFrame(token, false).toHex())

        val room = ActionCodec.unwrapFrame("01004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766010203".hexToBytes()) as ActionCodec.Frame.Room
        assertEquals(token, room.roomToken)
        assertEquals("010203", room.payload.toHex())
        val pres = ActionCodec.unwrapFrame("0201004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766".hexToBytes()) as ActionCodec.Frame.Presence
        assertTrue(pres.isPresent)
        assertTrue(!(ActionCodec.unwrapFrame("0200004065356533363930626162663137393762613939313233623062333261373561623666613438613462393834373061636565363263343962303462396631343766".hexToBytes()) as ActionCodec.Frame.Presence).isPresent)
        assertNull(ActionCodec.unwrapFrame(byteArrayOf(1, 0)))
    }

    @Test
    fun nostrEventIsSignedAndHashedLikeJsonStringify() {
        val ev = NostrProtocol.createEvent(22373, "topicXYZ", "{\"a\":\"q\\\"\"}\n日本語", 1700000000L)
        val body = org.json.JSONArray(ev).getJSONObject(1)
        val tags = body.getJSONArray("tags").toString()
        val serialized = "[0,\"${body.getString("pubkey")}\",1700000000,22373,$tags,${NostrProtocol.jsonString(body.getString("content"))}]"
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray()).toHex()
        assertEquals(id, body.getString("id"))
        assertEquals(22373, body.getInt("kind"))
        assertTrue(Schnorr.verify(body.getString("sig").hexToBytes(), id.hexToBytes(), body.getString("pubkey").hexToBytes()))
    }

    @Test
    fun subscribeRequestHasKindsAndTopics() {
        val req = org.json.JSONArray(NostrProtocol.subscribe("sub1", listOf("aaa", "bbb"), 1700000000L))
        assertEquals("REQ", req.getString(0))
        val f = req.getJSONObject(2)
        assertEquals(1700000000L, f.getLong("since"))
        assertEquals(2, f.getJSONArray("#x").length())
        val pres = org.json.JSONArray(NostrProtocol.subscribePresence("pochi-presence", 1700000000L)).getJSONObject(2)
        assertEquals(1699999995L, pres.getLong("since"))
        assertEquals(29334, pres.getJSONArray("kinds").getInt(0))
    }
}
