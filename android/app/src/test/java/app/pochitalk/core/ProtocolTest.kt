package app.pochitalk.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web版 (Trystero 0.20.0) と通話するための土台になる、署名・暗号・フレーミングの単体テスト。
 * 期待値は、本物の Trystero のコード (crypto.js / room.js) と @noble/curves で生成したもの。
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
    fun topicHashIsBase36PerByteLikeTrystero() {
        // Trystero: sha1("Trystero@appId@roomId") を各バイト toString(36) で連結 (JS側で生成した値)
        assertEquals(
            "156n19p4vo1r4c6a172g1q2aj653d3y55254f",
            TrysteroCrypto.sha1Topic("Trystero@pochitalk-walkie-talkie-cf-worker@h-3fa9c1d2"),
        )
    }

    @Test
    fun decryptsCiphertextMadeByTrysteroJs() {
        val key = TrysteroCrypto.genKey("", "pochitalk-walkie-talkie-cf-worker", "h-3fa9c1d2")
        val cipher = "111,153,95,7,75,7,251,88,11,31,80,101,99,16,7,145$" +
            "vFPt7gedvAd8TR7SBwsciDQT4S7HJeK72VpYPhZrwHdUPWQSM200ykCCd5Wm/9lCdPZnKerMpSOWWmtNisRJh9qvberGNWfYiMxmjLKOAHeu8qkVfgTI"
        assertEquals(
            "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\na=ice-ufrag:abc\r\n日本語🎙️",
            TrysteroCrypto.decrypt(key, cipher),
        )
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val key = TrysteroCrypto.genKey("", "a", "b")
        assertEquals("hello", TrysteroCrypto.decrypt(key, TrysteroCrypto.encrypt(key, "hello")))
    }

    @Test
    fun framingMatchesTrysteroRoomJs() {
        // room.js が実際に送ったバイト列 (pttState {isTalking:true} nonce=0 / pttName "たろう" nonce=0)
        val state = "7074745374617465000000000009ff7b22697354616c6b696e67223a747275657d"
        val name = "7074744e616d6500000000000001ffe3819fe3828de38186"
        assertEquals(state, ActionCodec.encodeJson("pttState", JSONObject().put("isTalking", true), 0)[0].toHex())
        assertEquals(name, ActionCodec.encodeString("pttName", "たろう", 0)[0].toHex())

        val re = ActionCodec.Reassembler()
        val m = re.feed(state.hexToBytes())
        assertNotNull(m)
        assertEquals("pttState", m!!.type)
        assertTrue(m.isJson)
        assertTrue(m.json().getBoolean("isTalking"))
        assertEquals("たろう", re.feed(name.hexToBytes())!!.text)
    }

    @Test
    fun multiChunkRoundTrip() {
        val big = "x".repeat(40_000)
        val re = ActionCodec.Reassembler()
        var got: ActionCodec.Message? = null
        ActionCodec.encodeString("bigtest", big, 7).forEach { got = re.feed(it) ?: got }
        assertEquals(big, got?.text)
    }

    @Test
    fun nostrEventIsSignedAndHashedLikeJsonStringify() {
        val ev = NostrProtocol.createEvent("topicXYZ", "{\"a\":\"q\\\"\"}\n日本語", 1700000000L)
        val body = org.json.JSONArray(ev).getJSONObject(1)
        val tags = body.getJSONArray("tags").toString()
        val serialized = "[0,\"${body.getString("pubkey")}\",1700000000,29333,$tags,${NostrProtocol.jsonString(body.getString("content"))}]"
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray()).toHex()
        assertEquals(id, body.getString("id"))
        assertTrue(Schnorr.verify(body.getString("sig").hexToBytes(), id.hexToBytes(), body.getString("pubkey").hexToBytes()))
    }
}
