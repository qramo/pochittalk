globalThis.window = globalThis
globalThis.addEventListener = () => {}
const {joinRoom, selfId} = await import('./ts/nostr.js')
const room = joinRoom({appId: 'pochitalk-walkie-talkie-cf-worker', relayUrls: ['wss://relay.nostr.net', 'wss://relay.snort.social', 'wss://relay.piazza.today']}, 'h-deadbeef')
console.log('JS selfId', selfId)
room.onPeerJoin(id => console.log('JS peer joined', id))
setTimeout(() => process.exit(0), 45000)
