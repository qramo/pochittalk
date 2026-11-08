import Foundation
import os

/// 通信まわりの状態はすべてこの直列キューで触る。
/// ブラウザ (JavaScript, シングルスレッド) 向けに書かれた Trystero / simple-peer のロジックを
/// そのまま移植しているので、排他制御を入れずに済むようにするための土台 (Android 版 Runtime.kt と同じ考え方)。
/// WebRTC / URLSession のコールバックは別スレッドで来るので、必ず RT.post で戻してから処理する。
enum RT {
    static let queue = DispatchQueue(label: "app.pochitalk.engine")
    private static let logger = Logger(subsystem: "app.pochitalk", category: "engine")
    private static let key = DispatchSpecificKey<Bool>()
    private static let _setup: Void = { queue.setSpecific(key: key, value: true) }()

    static func log(_ msg: String) {
        logger.debug("\(msg, privacy: .public)")
        #if DEBUG
        print("[Pochi] \(msg)") // 実機を Mac につないで --console で起動したときに読めるように
        #endif
    }

    static var onQueue: Bool { _ = _setup; return DispatchQueue.getSpecific(key: key) == true }

    static func post(_ block: @escaping () -> Void) {
        _ = _setup
        queue.async(execute: block)
    }

    @discardableResult
    static func after(_ ms: Int, _ block: @escaping () -> Void) -> Job {
        _ = _setup
        let job = Job()
        // Job を強く持たせる: 呼び出し側が戻り値を保持しなくても、予約は必ず実行される (取り消したときだけ実行されない)。
        // 実行したあと (または取り消したあと) は、循環を切って解放する。
        let item = DispatchWorkItem {
            let run = !job.cancelled
            job.item = nil
            if run { block() }
        }
        job.item = item
        queue.asyncAfter(deadline: .now() + .milliseconds(ms), execute: item)
        return job
    }

    /// 一定間隔で繰り返す (返した Job を cancel すると止まる)。
    @discardableResult
    static func every(_ ms: Int, _ block: @escaping () -> Void) -> Job {
        let job = Job()
        func tick() {
            if job.isCancelled { return }
            block()
            job.chain(after(ms) { tick() })
        }
        job.chain(after(ms) { tick() })
        return job
    }

    static func nowMs() -> Int { Int(Date().timeIntervalSince1970 * 1000) }
}

/// 取り消せる予約 (Kotlin の Job 相当)。
final class Job {
    fileprivate var item: DispatchWorkItem?
    fileprivate(set) var cancelled = false
    private var next: Job?
    var isCancelled: Bool { cancelled }
    func cancel() { cancelled = true; item?.cancel(); item = nil; next?.cancel() }
    /// 次の予約をつなげる (繰り返しの予約を、元の Job で止められるようにする)。
    func chain(_ job: Job) { next?.cancel(); next = job; if cancelled { job.cancel() } }
}
