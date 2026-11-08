import CoreGraphics
import Foundation

/// 話すボタンの押し方の判定 (画面の描画から切り離した状態遷移。Android 版 PttGesture.kt と同じ)。
///
///   IDLE ──押す──▶ WAITING ──[holdMs たつ]──▶ TALKING ──離す──▶ 送信を終える
///                    │                            │
///                    ├─ 離す (短いタップ)           └─ 指が円の外に出る ─▶ ABORT: すぐ止める
///                    └─ 指が円の外に出る ─▶ SWIPE: 送信しない
///
/// 円の上にいる間は、どれだけ動いてもスワイプとみなさない。円の縁から約14pt以上離れたときだけ、スワイプとみなす。
/// いったんスワイプになったら、円に戻っても再開しない。
struct PttGesture {
    enum State { case idle, waiting, talking, swipe }
    enum Action { case none, start, release, swipe, abort }

    let center: CGPoint
    let outsideRadius: CGFloat
    var holdMs: Double = 50
    private(set) var state: State = .idle
    private var downTime: Double = 0

    init(center: CGPoint, outsideRadius: CGFloat, holdMs: Double = 50) {
        self.center = center; self.outsideRadius = outsideRadius; self.holdMs = holdMs
    }

    func isOutside(_ p: CGPoint) -> Bool { hypot(p.x - center.x, p.y - center.y) > outsideRadius }

    mutating func onDown(_ timeMs: Double, _ p: CGPoint) {
        downTime = timeMs
        state = isOutside(p) ? .swipe : .waiting
    }

    mutating func onMove(_ timeMs: Double, _ p: CGPoint) -> Action {
        let outside = isOutside(p)
        switch state {
        case .waiting:
            if outside { state = .swipe; return .swipe }
            if timeMs - downTime >= holdMs { return start() }
            return .none
        case .talking:
            if outside { state = .swipe; return .abort }
            return .none
        default: return .none
        }
    }

    mutating func onTimer(_ timeMs: Double) -> Action {
        state == .waiting && timeMs - downTime >= holdMs ? start() : .none
    }

    mutating func onUp() -> Action {
        let was = state
        state = .idle
        return was == .talking ? .release : .none
    }

    private mutating func start() -> Action { state = .talking; return .start }
}
