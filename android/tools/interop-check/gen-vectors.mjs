import {sha1, genKey, encrypt, decrypt} from './ts/crypto.js'
import room from './ts/room.js'
import {schnorr} from '@noble/curves/secp256k1'
import {createHash} from 'node:crypto'
import fs from 'node:fs'

const hex = u8 => Buffer.from(u8).toString('hex')
const out = {}

// 1) topic
out.topicIn = 'Trystero@pochitalk-walkie-talkie-cf-worker@h-3fa9c1d2'
out.topicOut = await sha1(out.topicIn)

// 2) AES
out.appId = 'pochitalk-walkie-talkie-cf-worker'; out.roomId = 'h-3fa9c1d2'
const key = genKey('', out.appId, out.roomId)
out.plain = 'v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\na=ice-ufrag:abc\r\n日本語🎙️'
out.cipher = await encrypt(key, out.plain)

// 3) schnorr
out.priv = '0b1c2d3e4f5a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0'
out.aux = '1111111111111111111111111111111111111111111111111111111111111111'
out.msg = createHash('sha256').update('hello nostr').digest('hex')
out.pub = hex(schnorr.getPublicKey(out.priv))
out.sig = hex(await schnorr.sign(out.msg, out.priv, out.aux))

// 4) framing through the real room.js with a fake peer
let onPeerCb
const r = room(f => (onPeerCb = f), () => {}, () => {})
const sent = []
const fakePeer = {
  handlers: {},
  setHandlers(h) { Object.assign(this.handlers, h) },
  sendData(d) { sent.push(hex(d)) },
  channel: {bufferedAmount: 0, bufferedAmountLowThreshold: 0, addEventListener() {}, removeEventListener() {}},
  drainEarlyData() {}, destroy() {}, connection: {}
}
onPeerCb(fakePeer, 'peerA')
const [sendState] = r.makeAction('pttState')
const [sendName] = r.makeAction('pttName')
const [sendPresence] = r.makeAction('presence')
await sendState({isTalking: true}, 'peerA')
await sendState({isTalking: false}, 'peerA')
await sendName('たろう', 'peerA')
await sendPresence({ch: '3fa9c1d2', talking: false, name: 'はなこ'}, 'peerA')
out.frames = sent.slice()           // [state true, state false, name, presence]  (nonce 0,1,0,0)
// internal signal frames were sent too? clear and send a signal-like json via @_signal
out.sentCount = sent.length
fs.writeFileSync('vectors.json', JSON.stringify(out, null, 1))
console.log(JSON.stringify(out, null, 1).slice(0, 1500))
