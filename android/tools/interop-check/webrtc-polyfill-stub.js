// テスト用スタブ: 実際のWebRTCは使わず、simple-peer がオファーを作って送るところまでを再現する
export class RTCSessionDescription { constructor(d){ Object.assign(this, d) } }
export class RTCIceCandidate { constructor(d){ Object.assign(this, d) } }
export class RTCPeerConnection {
  constructor() { this.signalingState = 'stable'; this.iceConnectionState = 'new'; this.iceGatheringState = 'new'; this.localDescription = null; this.remoteDescription = null }
  createDataChannel(label) { return { label, readyState: 'connecting', bufferedAmount: 0, send(){}, close(){} } }
  async createOffer() { return { type: 'offer', sdp: 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\na=ice-options:trickle\r\na=fake-offer-from-trystero-js\r\n' } }
  async createAnswer() { return { type: 'answer', sdp: 'v=0\r\na=fake-answer\r\n' } }
  async setLocalDescription(d) { this.localDescription = d; setTimeout(() => this.onicecandidate?.({ candidate: null }), 5) }
  async setRemoteDescription(d) { this.remoteDescription = d }
  async addIceCandidate() {}
  getStats() { return Promise.resolve(new Map()) }
  close() {}
}
