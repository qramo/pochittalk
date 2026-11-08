import Foundation

/// 1本の Nostr リレーとの WebSocket。切れたら指数バックオフで再接続し、購読(REQ)を再送する
/// (Android 版 NostrClient.kt の移植)。すべて RT.queue 上で呼ぶこと。
final class NostrClient: NSObject, URLSessionWebSocketDelegate {
    let index: Int
    let url: String
    /// topic は、イベントのタグ ["x", topic] の値 (無ければ空文字)。
    private let onEvent: (_ client: NostrClient, _ subId: String, _ topic: String, _ content: String, _ event: [String: Any]) -> Void
    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private(set) var isOpen = false
    private var closed = false
    private var generation = 0
    private var retryMs = 3333
    private var subs: [String: () -> String] = [:]
    private var pingJob: Job?

    init(index: Int, url: String, onEvent: @escaping (NostrClient, String, String, String, [String: Any]) -> Void) {
        self.index = index
        self.url = url
        self.onEvent = onEvent
        super.init()
        let cfg = URLSessionConfiguration.default
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }

    func connect() {
        guard !closed, let u = URL(string: url) else { return }
        generation += 1
        let gen = generation
        let t = session.webSocketTask(with: u)
        task = t
        t.resume()
        receive(t, gen)
    }

    private func receive(_ t: URLSessionWebSocketTask, _ gen: Int) {
        t.receive { [weak self] result in
            RT.post {
                guard let self, gen == self.generation else { return }
                switch result {
                case .success(let msg):
                    if case .string(let text) = msg { self.handleMessage(text) }
                    self.receive(t, gen)
                case .failure(let err):
                    RT.log("relay failure: \(self.url) \(err.localizedDescription)")
                    self.handleClosed()
                }
            }
        }
    }

    // 接続が開いたことは delegate で知る
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        RT.post { [weak self] in
            guard let self, webSocketTask === self.task else { return }
            self.isOpen = true
            self.retryMs = 3333
            RT.log("relay open: \(self.url)")
            for b in self.subs.values { self.rawSend(b()) }
            self.startPing(webSocketTask, self.generation)
        }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        RT.post { [weak self] in
            guard let self, webSocketTask === self.task else { return }
            self.handleClosed()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard error != nil else { return }
        RT.post { [weak self] in
            guard let self, task === self.task else { return }
            self.handleClosed()
        }
    }

    private func startPing(_ t: URLSessionWebSocketTask, _ gen: Int) {
        pingJob?.cancel()
        pingJob = RT.after(30_000) { [weak self] in
            guard let self, gen == self.generation, self.isOpen else { return }
            t.sendPing { err in
                if err != nil { RT.post { if gen == self.generation { self.handleClosed() } } }
            }
            self.startPing(t, gen)
        }
    }

    private func handleClosed() {
        guard task != nil || isOpen else { return }
        isOpen = false
        pingJob?.cancel()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        generation += 1
        if closed { return }
        let wait = retryMs
        retryMs = min(retryMs * 2, 60_000)
        RT.after(wait) { [weak self] in self?.connect() }
    }

    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [Any],
              let kind = arr.first as? String else { return }
        switch kind {
        case "EVENT":
            guard arr.count >= 3, let subId = arr[1] as? String, let ev = arr[2] as? [String: Any],
                  let content = ev["content"] as? String else { return }
            var topic = ""
            for t in (ev["tags"] as? [[Any]]) ?? [] where (t.first as? String) == NostrProtocol.tag {
                topic = (t.count > 1 ? t[1] as? String : nil) ?? ""
                break
            }
            onEvent(self, subId, topic, content, ev)
        case "NOTICE":
            RT.log("relay notice \(url): \(arr.count > 1 ? "\(arr[1])" : "")")
        case "OK":
            if arr.count > 3, (arr[2] as? Bool) == false { RT.log("relay rejected \(url): \(arr[3])") }
        default: break
        }
    }

    private func rawSend(_ text: String) {
        task?.send(.string(text)) { _ in }
    }

    @discardableResult
    func send(_ text: String) -> Bool {
        guard isOpen, task != nil else { return false }
        rawSend(text)
        return true
    }

    /// 購読する。builder は接続のたびに呼ばれる (since を最新にするため)。
    func subscribe(subId: String, builder: @escaping () -> String) {
        subs[subId] = builder
        send(builder())
    }

    func unsubscribe(subId: String) {
        subs.removeValue(forKey: subId)
        send(NostrProtocol.unsubscribe(subId))
    }

    @discardableResult
    func publish(topic: String, content: String) -> Bool { send(NostrProtocol.createTopicEvent(topic: topic, content: content)) }

    @discardableResult
    func publishRaw(_ event: String) -> Bool { send(event) }

    /// いまの接続を捨てて、つなぎ直す (購読は保持)。回線が切り替わったあとの死んだ接続を張り直すために使う。
    func reconnect() {
        guard !closed else { return }
        generation += 1
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        isOpen = false
        retryMs = 3333
        connect()
    }

    /// 接続を取り直さずに「生き返らせる」(アプリが前面に戻ったとき・回線が戻ったときに使う)。
    ///  - 再試行の待ち時間をリセット
    ///  - 閉じかけ・閉じたまま残った接続は捨てて、つなぎ直す
    ///  - 開いている接続は、購読 (REQ) をやり直し、ping で生きているか確かめる (返らなければ、つなぎ直す)
    /// 捨てた古い接続の通知は、generation と task の照合で無視されるので、新しい接続の状態を壊さない。
    func refresh() {
        guard !closed else { return }
        retryMs = 3333
        guard let t = task, isOpen, t.state == .running else { reconnect(); return }
        for b in subs.values { rawSend(b()) }
        let gen = generation
        t.sendPing { [weak self] err in
            guard err != nil else { return }
            RT.post {
                guard let self, gen == self.generation, !self.closed else { return }
                RT.log("relay ping failed, reconnecting: \(self.url)")
                self.reconnect()
            }
        }
    }

    func close() {
        closed = true
        isOpen = false
        generation += 1
        pingJob?.cancel()
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        session.invalidateAndCancel()
    }
}
