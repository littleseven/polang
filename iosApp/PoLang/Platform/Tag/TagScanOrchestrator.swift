import Foundation
import UIKit

/// 扫描进度快照（对齐 Android TagScanSessionProgress）。
struct TagScanSessionProgress: Sendable, Equatable {
    var sessionId: String
    var state: ScanSessionState
    var currentPass: ScanPass
    var processed: Int
    var total: Int
    var pending: Int
    var failed: Int
    var estimatedRemainingMs: Int
    var message: String
}

/// 扫描事件（orchestrator → ViewModel）。
enum ScanEvent: Sendable {
    case progress(TagScanSessionProgress)
    case finished(ScanSessionState) // completed / cancelled
    case modelsNeeded // 模型未就绪（glintr100/mobileclip 未下载）→ 提示用户去 Model Center
}

/// 按需图像理解结果（gallery 大图页「图像理解」入口，对齐 Android describeImage）。
enum VisionDescribeOutcome {
    case success(String)        // Florence-2 caption（英文，端侧）
    case modelsNotDownloaded    // Florence-2 模型未下载 → 提示去 Model Center
    case lowMemory              // 可用内存 <300MB（防 OS SIGKILL）
    case failed                 // 加载/推理失败
}

/// TAG 扫描编排器。
///
/// 设计：可变状态经串行 `lockQueue` 保护（与 TagDatabase 同风格）；单一后台 `Task(.utility)`
/// 串行调 `Pass1Pipeline.shared`（MNN 单线程约束）；pause/cancel 协作式（每张图之间检查）；
/// 进度经 `onEvent` 回调发出（订阅方自行切主线程）。前台优先：进后台由调用方触发 `pause()`。
final class TagScanOrchestrator: @unchecked Sendable {
    static let shared = TagScanOrchestrator()

    /// 进度回调（ViewModel 订阅；在 lockQueue 或后台线程发出，订阅方需自行切主线程）。
    var onEvent: (@Sendable (ScanEvent) -> Void)?

    private let db = TagDatabase.shared
    private let media = PhMediaBridge()
    private let lockQueue = DispatchQueue(label: "com.mamba.picme.tagscan", qos: .userInitiated)

    private struct Box {
        var sessionState: ScanSessionState = .idle
        var sessionId: String?
        var pauseRequested: Bool = false
        var cancelRequested: Bool = false
        var samples: [Int] = []
        var processed: Int = 0
        var failed: Int = 0
        var total: Int = 0
        var task: Task<Void, Never>?
        var pass3Enqueued: Bool = false
        var pass3Only: Bool = false
    }
    private var box = Box()

    /// Florence-2 打标器（Pass3），懒加载
    private var florence2Tagger: Florence2Tagger?

    /// Florence-2 模型目录：Documents/llm_models/florence2_base/
    private var florence2ModelDir: String {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("llm_models")
            .appendingPathComponent("florence2_base")
            .path
    }

    private init() {
        // 每次启动清除 Florence-2 崩溃标记（让 Pass3 总能触发）
        UserDefaults.standard.set(false, forKey: "florence2_attempting")
        // 中断恢复：把上次 RUNNING 重置为 PENDING，等用户在扫描页点恢复。
        if let sid = db.unfinishedSessionId() {
            db.resetRunningToPending(sessionId: sid)
        }
    }

    // MARK: - 锁内读写

    @inline(__always)
    private func mutate<T>(_ body: (inout Box) -> T) -> T { lockQueue.sync { body(&self.box) } }
    private func read<T>(_ body: (Box) -> T) -> T { lockQueue.sync { body(self.box) } }

    private func snapshot() -> TagScanSessionProgress {
        mutate { box in
            let eta = ScanEtaEstimator(pass: .faceDetection, samples: box.samples)
                .estimateMillis(remaining: max(0, box.total - box.processed))
            let pending = max(0, box.total - box.processed)
            return TagScanSessionProgress(
                sessionId: box.sessionId ?? "",
                state: box.sessionState,
                currentPass: .faceDetection,
                processed: box.processed, total: box.total,
                pending: pending, failed: box.failed,
                estimatedRemainingMs: eta,
                message: box.sessionState.localizationKey)
        }
    }

    // MARK: - 公共控制（UI 在 MainActor 调）

    /// 整理中心质量分回填错峰门（organize-port-plan 风险 2）：会话活跃（含暂停中——
    /// 随时可恢复）时回填整体让路，避免与 Pass1/3 争 IO 与 TagDatabase 串行队列。
    var isSessionActive: Bool {
        read { box in
            switch box.sessionState {
            case .running, .pausing, .paused, .cancelling: return true
            case .idle, .cancelled, .completed: return false
            }
        }
    }

    /// 启动新扫描会话。
    func start(mode: ScanMode) {
        NSLog("PoLang:TagScan start mode=\(mode.rawValue) isMain=\(Thread.isMainThread)")
        let canStart = read { $0.sessionState == .idle || $0.sessionState.isTerminal }
        guard canStart else { NSLog("PoLang:TagScan start rejected (busy)"); return }

        // 1) 同步 media_assets 索引（get-or-create 全量，不持 lockQueue）
        let now = Self.nowMs()
        let fetched = media.fetchAllMedia()
        NSLog("PoLang:TagScan fetched \(fetched.count) media; indexing isMain=\(Thread.isMainThread)")
        for item in fetched where item.mediaType == "PHOTO" {
            db.getOrCreateMedia(localIdentifier: item.localIdentifier,
                                type: "IMAGE",
                                captureDateMs: item.captureDateMs,
                                fileName: item.fileName)
        }
        NSLog("PoLang:TagScan indexed media_assets")
        // reconcile：清理已从系统相册删除的孤儿 media_assets（防线2，兜底任何来源的删除：
        // 系统相册 app / 其它 app）。复用已在作用域的 fetched，不二次访问 Photos framework。
        let systemLids = Set(fetched.filter { $0.mediaType == "PHOTO" }.map { $0.localIdentifier })
        db.reconcileMediaAssets(keepLocalIdentifiers: systemLids)
        // 2) 规划 Pass1 任务集
        let allIds = db.allImageMediaIds()
        let covered = db.pass1CoveredMediaIds()
        let planned = ScanTaskPlanner.pass1TaskIds(
            allImageMediaIds: allIds, pass1CoveredMediaIds: covered, mode: mode)

        let sid = "tag-\(UUID().uuidString.prefix(8))"
        if planned.isEmpty {
            // 无待扫：直接完成
            let snap = mutate { box -> TagScanSessionProgress in
                box.sessionId = sid
                box.sessionState = .completed
                box.total = 0; box.processed = 0; box.failed = 0
                return self.snapshotLocked(box)
            }
            emit(.progress(snap)); emit(.finished(.completed))
            return
        }
        // 3) 入队（先清空旧会话残留任务，防累积——否则任务数远超照片数、扫描不终止）
        db.clearAllTasks()
        if mode == .full {
            // 全量扫描：硬重置人脸聚类产物（embedding/person/relation + media 脸部字段），
            // 从 0 重新计数——Pass1 重新生成、Pass2 重新聚类，结果只反映本次扫描。
            db.clearAllFaceClusteringData()
        }
        db.enqueuePass1Tasks(sessionId: sid, mediaIds: planned, now: now)
        // 4) 置状态 + 启运行循环（持锁）
        let snap = mutate { box -> TagScanSessionProgress in
            box.task?.cancel()
            box.sessionId = sid
            box.sessionState = box.sessionState.transition(.start) ?? .running
            box.total = planned.count
            box.processed = 0; box.failed = 0
            box.samples = []
            box.pauseRequested = false; box.cancelRequested = false
            box.pass3Enqueued = false
            box.pass3Only = false
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        emit(.progress(snap))
    }

    func pause() {
        let snap = mutate { box -> TagScanSessionProgress? in
            guard box.sessionState == .running else { return nil }
            box.pauseRequested = true
            box.sessionState = box.sessionState.transition(.pause) ?? .pausing
            return self.snapshotLocked(box)
        }
        if let snap = snap { emit(.progress(snap)) }
    }

    func resume() {
        let snap = mutate { box -> TagScanSessionProgress? in
            guard box.sessionState == .paused, let sid = box.sessionId else { return nil }
            self.db.resumeSession(sessionId: sid)
            box.pauseRequested = false
            box.sessionState = box.sessionState.transition(.resume) ?? .running
            box.task?.cancel()
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        if let snap = snap { emit(.progress(snap)) }
    }

    func cancel() {
        let evt: ScanEvent? = mutate { box -> ScanEvent? in
            guard let sid = box.sessionId, !box.sessionState.isTerminal else { return nil }
            box.cancelRequested = true
            self.db.cancelSession(sessionId: sid)
            box.task?.cancel()
            box.sessionState = .cancelling.transition(.cancelAcknowledged) ?? .cancelled
            box.task = nil
            return .finished(.cancelled)
        }
        if let evt = evt {
            emit(.progress(snapshot()))
            emit(evt)
        }
    }

    /// 恢复上次未完成 session（App 重启后扫描页「恢复」用）。
    /// 从 DB 采纳未完成 sessionId，置 running 并启动循环（不重新入队）。
    func resumeUnfinishedSession() {
        scanDebugLog("resumeUnfinished enter; unfinishedSid=\(db.unfinishedSessionId() ?? "nil")")
        guard let sid = db.unfinishedSessionId() else {
            scanDebugLog("resumeUnfinished: no sid, return")
            return
        }
        let counts = db.sessionCounts(sid)
        let snap = mutate { box -> TagScanSessionProgress in
            box.task?.cancel()
            box.sessionId = sid
            box.sessionState = box.sessionState.transition(.start) ?? .running
            box.total = counts.total
            box.processed = counts.completed
            box.failed = counts.failed
            box.samples = []
            box.pauseRequested = false; box.cancelRequested = false
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        emit(.progress(snap))
    }

    func retryFailed() {
        let resumed = mutate { box -> Bool in
            guard let sid = box.sessionId else { return false }
            self.db.retryFailed(sessionId: sid)
            let canRun = box.sessionState == .paused || box.sessionState == .idle || box.sessionState.isTerminal
            if canRun {
                box.sessionState = .running
                box.cancelRequested = false; box.pauseRequested = false
                box.task?.cancel()
                box.task = Task.detached(priority: .utility) { [weak self] in
                    await self?.runLoop()
                }
            }
            return canRun
        }
        if resumed { emit(.progress(snapshot())) }
    }

    /// 当前进度快照（UI 刷新用）。
    func currentProgress() -> TagScanSessionProgress? {
        read { box in box.sessionState == .idle ? nil : self.snapshotLocked(box) }
    }

    /// 是否有未完成 session（扫描页「恢复」提示用）。
    var hasUnfinishedSession: Bool { db.unfinishedSessionId() != nil }

    /// 前台优先：进后台时由调用方触发（仅 running 时协作暂停）。
    func pauseForBackground() { pause() }

    // MARK: - 分阶段独立控制（PassControlCard 入口）

    /// 手动触发 Pass2 聚类（直接跑，不需任务队列）。点 Pass2 卡片时立即显示「聚类中」反馈，
    /// 完成后显示「完成」并 emit .finished 触发 ViewModel 刷新统计。
    func runPass2Clustering() {
        emit(.progress(TagScanSessionProgress(
            sessionId: "pass2-manual", state: .running, currentPass: .dbscan,
            processed: 0, total: 0, pending: 0, failed: 0,
            estimatedRemainingMs: 0, message: ScanSessionState.running.localizationKey)))
        Task.detached(priority: .utility) { [weak self] in
            scanDebugLog("TS runPass2Clustering start")
            let n = Pass2Pipeline.runClustering()
            scanDebugLog("TS runPass2Clustering done: \(n) persons")
            guard let self else { return }
            self.emit(.progress(TagScanSessionProgress(
                sessionId: "pass2-manual", state: .completed, currentPass: .dbscan,
                processed: 0, total: 0, pending: 0, failed: 0,
                estimatedRemainingMs: 0, message: ScanSessionState.completed.localizationKey)))
            self.emit(.finished(.completed))
        }
    }

    /// 手动触发 Pass3 内容打标（增量/全量）。
    func startPass3(mode: ScanMode) {
        // 崩溃防护：上次 Florence-2 推理崩溃 → 跳过 Pass3
        if UserDefaults.standard.bool(forKey: "florence2_attempting") {
            scanDebugLog("TS startPass3 SKIPPED — Florence-2 上次崩溃，已禁用")
            emit(.modelsNeeded)
            return
        }
        scanDebugLog("TS startPass3 mode=\(mode.rawValue)")
        let canStart = read { $0.sessionState == .idle || $0.sessionState.isTerminal }
        guard canStart else { scanDebugLog("TS startPass3 rejected (not idle)"); return }

        guard Florence2Tagger.modelsAvailable(modelDir: florence2ModelDir) else {
            emit(.modelsNeeded)
            return
        }

        let allIds = db.allImageMediaIds()
        let covered = mode == .incremental ? db.labelsEnCoveredMediaIds() : []
        let planned = allIds.filter { !covered.contains($0) }
        scanDebugLog("TS startPass3: planned=\(planned.count) tasks")
        guard !planned.isEmpty else {
            scanDebugLog("TS Pass3: nothing to tag")
            return
        }

        let sid = "tag-\(UUID().uuidString.prefix(8))"
        db.enqueueImageTaggingTasks(sessionId: sid, mediaIds: planned, now: Self.nowMs())
        scanDebugLog("TS Pass3 start: \(planned.count) tasks")

        let snap = mutate { box -> TagScanSessionProgress in
            box.task?.cancel()
            box.sessionId = sid
            box.sessionState = .running
            box.total = planned.count
            box.processed = 0; box.failed = 0
            box.samples = []
            box.pauseRequested = false; box.cancelRequested = false
            box.pass3Enqueued = true
            box.pass3Only = true
            box.task = Task.detached(priority: .utility) { [weak self] in
                await self?.runLoop()
            }
            return self.snapshotLocked(box)
        }
        emit(.progress(snap))
    }

    // MARK: - 运行循环（后台 Task）

    private func runLoop() async {
        scanDebugLog("TS runLoop enter isMain=\(Thread.isMainThread)")
        // Pass3-only 会话不加载 Pass1 的 MNN 模型（省 ~660MB 内存给 Florence-2 ORT）。
        let isPass3Only = read { $0.pass3Only }
        if !isPass3Only {
            // 确保 Pass1 模型已加载
            if !Pass1Pipeline.shared.modelsReady {
                let ok = Pass1Pipeline.shared.loadModels()
                scanDebugLog("TS loadModels ok=\(ok) ready=\(Pass1Pipeline.shared.modelsReady)")
            }
            guard Pass1Pipeline.shared.modelsReady else {
                scanDebugLog("TS abort: models NOT ready")
                mutate { box in box.sessionState = .idle; box.task = nil }
                emit(.modelsNeeded)
                return
            }
        } else {
            scanDebugLog("TS Pass3-only: skip Pass1 MNN models (save ~660MB)")
        }
        let sid = read { $0.sessionId } ?? ""
        var localSamples: [Int] = []
        while !Task.isCancelled {
            // 1) 检查控制标志
            let (pauseReq, cancelReq) = read { ($0.pauseRequested, $0.cancelRequested) }
            if cancelReq { return }                  // cancel() 已置状态/发事件
            if pauseReq {
                db.pauseSession(sessionId: sid)
                let snap = mutate { box -> TagScanSessionProgress in
                    box.sessionState = box.sessionState.transition(.pauseAcknowledged) ?? .paused
                    return self.snapshotLocked(box)
                }
                emit(.progress(snap))
                return
            }
            // 2) 原子领取任务
            let now = Self.nowMs()
            guard let task = db.pollAndMarkRunning(sessionId: sid, now: now) else {
                // 队列空：再次确认未被取消，否则完成
                let cancelled = read { $0.cancelRequested }
                if cancelled { return }

                // 判断是否已入队 Pass3
                let pass3Done = read { $0.pass3Enqueued }
                if !pass3Done {
                    // ── Pass1 会话结束 → 跑一次 Pass2 人物聚类（DBSCAN）──
                    scanDebugLog("TS Pass1 done → run Pass2 clustering")
                    _ = Pass2Pipeline.runClustering()

                    // ── 入队 Pass3（Florence-2 内容打标）──
                    let pass3Enqueued = enqueuePass3IfNeeded(sessionId: sid)
                    mutate { box in box.pass3Enqueued = pass3Enqueued }
                    if pass3Enqueued {
                        scanDebugLog("TS Pass3 tasks enqueued, continuing loop")
                        // 继续 runLoop 处理 Pass3 任务
                        try? await Task.sleep(nanoseconds: 50_000_000)
                        continue
                    }
                }

                // Pass3 已完成（或未入队）→ 会话结束
                scanDebugLog("TS All passes done → complete")
                let snap = mutate { box -> TagScanSessionProgress in
                    box.sessionState = box.sessionState.transition(.complete) ?? .completed
                    return self.snapshotLocked(box)
                }
                emit(.progress(snap)); emit(.finished(.completed))
                return
            }
            // 3) 按 pass 类型执行任务
            let lid = db.localIdentifier(forMediaId: task.mediaId)
            NSLog("PoLang:TagScan task mediaId=\(task.mediaId) pass=\(task.pass) lid=\(lid ?? "nil")")
            if task.pass == "IMAGE_TAGGING" {
                // ── Pass3: Florence-2 内容打标 ──
                processPass3Task(task: task, lid: lid, now: now)
            } else {
                // ── Pass1: 人脸检测 + embedding + MobileCLIP ──
                if let lid = lid, let image = ScanImageLoader.load(localIdentifier: lid) {
                    NSLog("PoLang:TagScan calling process mediaId=\(task.mediaId)")
                    let t0 = CFAbsoluteTimeGetCurrent()
                    _ = Pass1Pipeline.shared.process(image, mediaId: task.mediaId)
                    NSLog("PoLang:TagScan process done mediaId=\(task.mediaId)")
                    let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
                    localSamples.append(ms)
                    db.markCompleted(taskId: task.taskId, now: Self.nowMs())
                    let snap = mutate { box -> TagScanSessionProgress in
                        box.processed += 1
                        box.samples = localSamples
                        return self.snapshotLocked(box)
                    }
                    emit(.progress(snap))
                } else {
                    db.markFailed(taskId: task.taskId, now: now,
                                  errorMessage: "image load failed", backoffMs: 5_000)
                    let snap = mutate { box -> TagScanSessionProgress in
                        box.failed += 1
                        return self.snapshotLocked(box)
                    }
                    emit(.progress(snap))
                }
            }
            // 4) 任务间轻让步
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    // MARK: - 按需图像理解（gallery 大图页「图像理解」入口）

    /// on-demand 串行队列：保护 onDemandTagger，并串行化按需推理（与 Pass3 的 florence2Tagger
    /// 隔离，避免后台扫描与按需理解两条路径竞争同一 tagger 实例）。
    private let visionQueue = DispatchQueue(label: "com.mamba.picme.vision", qos: .userInitiated)
    /// 按需图像理解专用 Florence-2 实例（懒加载；与 Pass3 的 florence2Tagger 独立）。
    private var onDemandTagger: Florence2Tagger?

    /// 按需图像理解：对单张图返回 Florence-2 caption（英文，端侧，对齐 Android describeImage 的
    /// Florence-2 路径）。安全模式镜像 `processPass3Task`（modelsAvailable 检查 → 懒加载 →
    /// isLoaded → ≥300MB 内存保护 → `florence2_attempting` 崩溃标记）。
    ///
    /// - Warning: 同步阻塞（Florence-2 推理数秒），调用方**必须**在后台线程（`Task.detached`）调，
    ///   不可在主线程或持有协作线程关键资源的上下文直接调。
    func describeImage(_ image: UIImage) -> VisionDescribeOutcome {
        visionQueue.sync {
            // 1) 模型未下载
            guard Florence2Tagger.modelsAvailable(modelDir: florence2ModelDir) else {
                NSLog("PoLang:Florence2 onDemand: models not downloaded")
                return .modelsNotDownloaded
            }
            // 2) 懒加载（与 processPass3Task 一致）
            if onDemandTagger == nil {
                let tagger = Florence2Tagger()
                NSLog("PoLang:Florence2 onDemand: loading...")
                if tagger.load(modelDir: florence2ModelDir) {
                    onDemandTagger = tagger
                }
            }
            guard let tagger = onDemandTagger, tagger.isLoaded else {
                NSLog("PoLang:Florence2 onDemand: tagger not loaded")
                return .failed
            }
            // 3) 低内存保护（Florence-2 4 模型 + 中间张量吃内存，不足防 OS SIGKILL）
            let availMB = Int(os_proc_available_memory() / 1024 / 1024)
            guard availMB >= 300 else {
                NSLog("PoLang:Florence2 onDemand SKIP: low memory (\(availMB)MB < 300MB)")
                return .lowMemory
            }
            // 4) 崩溃标记 + 推理（与 Pass3 一致）
            NSLog("PoLang:Florence2 onDemand: calling tag...")
            UserDefaults.standard.set(true, forKey: "florence2_attempting")
            let t0 = CFAbsoluteTimeGetCurrent()
            let result = tagger.tag(image)
            UserDefaults.standard.set(false, forKey: "florence2_attempting")
            let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
            NSLog("PoLang:Florence2 onDemand: tag done in \(ms)ms result=\(result != nil)")
            guard let summary = result?.summary, !summary.isEmpty else { return .failed }
            return .success(summary)
        }
    }

    // MARK: - Pass3（Florence-2 内容打标）

    /// 尝试入队 Pass3 任务。如果 Florence-2 模型未下载或无待打标图片，返回 false。
    private func enqueuePass3IfNeeded(sessionId: String) -> Bool {
        // 检查 Florence-2 模型是否已下载
        let modelDir = florence2ModelDir
        guard Florence2Tagger.modelsAvailable(modelDir: modelDir) else {
            scanDebugLog("TS Pass3 skipped: Florence-2 models not downloaded")
            return false
        }
        // 规划 Pass3 任务集（增量：跳过已有 labelsEn 的图片）
        let allIds = db.allImageMediaIds()
        let covered = db.labelsEnCoveredMediaIds()
        let planned = allIds.filter { !covered.contains($0) }
        guard !planned.isEmpty else {
            scanDebugLog("TS Pass3 skipped: all images already tagged")
            return false
        }
        db.enqueueImageTaggingTasks(sessionId: sessionId, mediaIds: planned, now: Self.nowMs())
        scanDebugLog("TS Pass3 enqueued \(planned.count) image tagging tasks")
        return true
    }

    /// 执行单个 Pass3 任务（Florence-2 打标）。
    private func processPass3Task(task: TagDatabase.QueuedTask, lid: String?, now: Int64) {
        scanDebugLog("TS P3 task mediaId=\(task.mediaId)")
        // 懒加载 Florence2Tagger
        if florence2Tagger == nil {
            let tagger = Florence2Tagger()
            scanDebugLog("TS P3 loading Florence2Tagger...")
            let ok = tagger.load(modelDir: florence2ModelDir)
            scanDebugLog("TS P3 Florence2Tagger load ok=\(ok)")
            if ok {
                florence2Tagger = tagger
            }
        }
        guard let tagger = florence2Tagger, tagger.isLoaded else {
            scanDebugLog("TS P3 tagger not loaded, skip")
            db.markFailed(taskId: task.taskId, now: now,
                          errorMessage: "Florence-2 models not loaded", backoffMs: 60_000)
            let snap = mutate { box -> TagScanSessionProgress in
                box.failed += 1
                return self.snapshotLocked(box)
            }
            emit(.progress(snap))
            return
        }

        guard let lid = lid, let image = ScanImageLoader.load(localIdentifier: lid) else {
            db.markFailed(taskId: task.taskId, now: now,
                          errorMessage: "image load failed", backoffMs: 5_000)
            let snap = mutate { box -> TagScanSessionProgress in
                box.failed += 1
                return self.snapshotLocked(box)
            }
            emit(.progress(snap))
            return
        }

        // 内存检查：Florence-2 4 模型 + 推理中间张量需要大量内存，不足时跳过防 OS SIGKILL
        let availBytes = os_proc_available_memory()
        let availMB = Int(availBytes / 1024 / 1024)
        scanDebugLog("TS P3 mem check: \(availMB)MB available")
        if availMB < 300 {
            scanDebugLog("TS P3 SKIP: low memory (\(availMB)MB < 300MB), cannot run Florence-2")
            db.markFailed(taskId: task.taskId, now: now, errorMessage: "low memory (\(availMB)MB)", backoffMs: 60_000)
            let snap = mutate { box -> TagScanSessionProgress in
                box.failed += 1
                return self.snapshotLocked(box)
            }
            emit(.progress(snap))
            return
        }
        scanDebugLog("TS P3 calling tagger.tag mediaId=\(task.mediaId)")
        UserDefaults.standard.set(true, forKey: "florence2_attempting")
        let t0 = CFAbsoluteTimeGetCurrent()
        let result = tagger.tag(image)
        UserDefaults.standard.set(false, forKey: "florence2_attempting")  // 成功返回 → 清除标记
        let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        scanDebugLog("TS P3 tag done mediaId=\(task.mediaId) in \(ms)ms result=\(result != nil)")

        if let result = result {
            let labelsJson = Self.encodeLabelsEn(result.labelsEn)
            db.updateLabelsEn(mediaId: task.mediaId, labelsEn: labelsJson)
        } else {
            // 推理失败 → 写入空数组标记已处理（避免重试循环）
            db.updateLabelsEn(mediaId: task.mediaId, labelsEn: "[]")
        }
        db.markCompleted(taskId: task.taskId, now: Self.nowMs())
        let snap = mutate { box -> TagScanSessionProgress in
            box.processed += 1
            return self.snapshotLocked(box)
        }
        emit(.progress(snap))
    }

    /// 将标签数组编码为 JSON 字符串（labelsEn 列存储格式）。
    private static func encodeLabelsEn(_ labels: [String]) -> String {
        if let data = try? JSONSerialization.data(withJSONObject: labels),
           let str = String(data: data, encoding: .utf8) {
            return str
        }
        return "[]"
    }

    // MARK: - 辅助

    /// 由 box 构造进度快照（须在锁内调用）。
    private func snapshotLocked(_ box: Box) -> TagScanSessionProgress {
        let eta = ScanEtaEstimator(pass: .faceDetection, samples: box.samples)
            .estimateMillis(remaining: max(0, box.total - box.processed))
        return TagScanSessionProgress(
            sessionId: box.sessionId ?? "",
            state: box.sessionState,
            currentPass: .faceDetection,
            processed: box.processed, total: box.total,
            pending: max(0, box.total - box.processed),
            failed: box.failed,
            estimatedRemainingMs: eta,
            message: box.sessionState.localizationKey)
    }

    private func emit(_ ev: ScanEvent) { onEvent?(ev) }
    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
