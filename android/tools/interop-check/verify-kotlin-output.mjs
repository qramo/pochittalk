import {genKey, decrypt} from './ts/crypto.js'
import room from './ts/room.js'
import {schnorr} from '@noble/curves/secp256k1'
import {createHash} from 'node:crypto'
import fs from 'node:fs'
const k = JSON.parse(fs.readFileSync('kotlin-out.json','utf8'))
let fails = 0
const check = (n, ok) => { console.log((ok?'PASS ':'FAIL ')+n); if(!ok) fails++ }
const key = genKey('', 'pochitalk-walkie-talkie-cf-worker', 'h-3fa9c1d2')
check('JS decrypts Kotlin ciphertext', (await decrypt(key, k.cipher)) === 'kotlin→js 日本語 "q" \\ </script>')

// real room.js receiving Kotlin frames
let onPeerCb
const r = room(f => (onPeerCb = f), () => {}, () => {})
const fake = {h:{}, setHandlers(h){Object.assign(this.h,h)}, sendData(){}, channel:{bufferedAmount:0,bufferedAmountLowThreshold:0}, drainEarlyData(){}, destroy(){}, connection:{}}
onPeerCb(fake, 'peerK')
const got = {}
const [, onState] = r.makeAction('pttState'); onState((d, id) => got.state = [d, id])
const [, onName] = r.makeAction('pttName'); onName((d, id) => got.name = [d, id])
for (const f of k.frames) fake.h.data(Buffer.from(f, 'hex'))
check('room.js parses Kotlin pttState', got.state && got.state[0].isTalking === true && got.state[1] === 'peerK')
check('room.js parses Kotlin pttName', got.name && got.name[0] === 'あいう')

// nostr event
const ev = JSON.parse(k.event)[1]
check('event content roundtrip', ev.content === k.eventContent)
const id = createHash('sha256').update(JSON.stringify([0, ev.pubkey, ev.created_at, ev.kind, ev.tags, ev.content])).digest('hex')
check('event id equals JSON.stringify hash', id === ev.id)
check('event schnorr signature verifies (noble)', await schnorr.verify(ev.sig, ev.id, ev.pubkey))
check('event shape', ev.kind === 29333 && ev.tags[0][0] === 'x' && ev.tags[0][1] === 'topicXYZ')
console.log(fails ? 'FAILURES '+fails : 'ALL PASS')
process.exit(fails ? 1 : 0)
