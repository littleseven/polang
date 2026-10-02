import Foundation
import SharedKit

/// 模型配置持久化（UserDefaults）+ 应用到 AgentOrchestrator 的桥。
///
/// 对齐 Android 的 `UserSettingsRepository` 两个 key：
/// - `model_configs` — RemoteModelConfigs JSON（与 Android 互通格式）
/// - `selected_model_id` — 当前选中模型 id
///
/// 直接复用 SharedKit 的 `RemoteModelConfigs.companion.fromJson/toJson` 编解码，
/// 保证双端 JSON 格式一致（commonMain 手写 regex JSON，不走 Codable）。
@MainActor
final class ModelConfigStore: ObservableObject {
    static let shared = ModelConfigStore()

    private let configsKey = "model_configs"
    private let selectedKey = "selected_model_id"

    /// 已配置的模型列表（含 apiKey 非空的）
    @Published private(set) var configs: [RemoteModelConfig] = []

    /// 当前选中的模型 id
    @Published private(set) var selectedModelId: String = "deepseek-v4-flash"

    private init() {
        load()
    }

    // MARK: - Load / Save

    func load() {
        let defaults = UserDefaults.standard

        // 选中的模型 id
        selectedModelId = defaults.string(forKey: selectedKey) ?? "deepseek-v4-flash"

        // 配置列表
        if let json = defaults.string(forKey: configsKey), !json.isEmpty {
            let parsed = RemoteModelConfigs.companion.fromJson(json: json)
            configs = parsed.configs as? [RemoteModelConfig] ?? []
        } else {
            configs = []
        }
    }

    private func save() {
        let defaults = UserDefaults.standard
        let collection = RemoteModelConfigs(configs: configs)
        let json = RemoteModelConfigs.companion.toJson(configs: collection)
        defaults.set(json, forKey: configsKey)
        defaults.set(selectedModelId, forKey: selectedKey)
    }

    // MARK: - CRUD

    /// 添加模型（供应商 + 模型 + API Key）
    func add(provider: RemoteModelProvider, modelId: String, apiKey: String) {
        let config = RemoteModelConfig(
            modelId: modelId,
            providerId: provider.providerId,
            protocol: provider.protocol,
            apiKey: apiKey,
            baseUrl: provider.baseUrl,
            gatewayToken: "", deviceId: ""
        )
        // RemoteModelConfigs.addConfig 是 immutable（返回新实例），这里直接操作数组
        let uniqueKey = config.uniqueKey
        configs.removeAll { $0.uniqueKey == uniqueKey }
        configs.append(config)
        save()
        applyToOrchestrator()
    }

    /// 删除模型（删的是当前选中项则自动改选下一个 isConfigured，spec §3 remote_model action_sheet）
    func remove(uniqueKey: String) {
        // selectedModelId 存 modelId，而 uniqueKey = providerId:modelId（含前缀不可直比），按删除项 modelId 匹配
        let removedModelId = configs.first { $0.uniqueKey == uniqueKey }?.modelId
        configs.removeAll { $0.uniqueKey == uniqueKey }
        if let removedModelId, removedModelId == selectedModelId {
            selectedModelId = configs.first(where: { $0.isConfigured })?.modelId ?? "deepseek-v4-flash"
        }
        save()
        applyToOrchestrator()
    }

    /// 选中模型
    func select(modelId: String) {
        selectedModelId = modelId
        save()
        applyToOrchestrator()
    }

    /// 更新已有模型的 API Key（对齐 Android RemoteModelConfigCard 编辑：预定义模型改 apiKey）
    func updateApiKey(uniqueKey: String, apiKey: String) {
        guard let idx = configs.firstIndex(where: { $0.uniqueKey == uniqueKey }) else { return }
        let old = configs[idx]
        configs[idx] = RemoteModelConfig(
            modelId: old.modelId,
            providerId: old.providerId,
            protocol: old.protocol,
            apiKey: apiKey,
            baseUrl: old.baseUrl,
            gatewayToken: old.gatewayToken,
            deviceId: old.deviceId
        )
        save()
        applyToOrchestrator()
    }

    // MARK: - Active Config

    /// 当前生效的配置：选中且 isConfigured → 用户配置；否则 PICME_SERVER_DEFAULT（访客模式）
    func activeConfig() -> RemoteModelConfig {
        // 找选中模型对应的已配置 config
        let selected = configs.first { $0.modelId == selectedModelId && $0.isConfigured }
        if let selected {
            return selected
        }
        // 选中的没配 key，但有其他已配置的 → 用第一个
        let firstConfigured = configs.first(where: { $0.isConfigured })
        if let firstConfigured {
            selectedModelId = firstConfigured.modelId
            return firstConfigured
        }
        // 全没配 → 访客模式
        return RemoteModelConfig.companion.PICME_SERVER_DEFAULT
    }

    /// 应用配置到 AgentOrchestrator（触发 Koog Agent 下次推理时重建）
    func applyToOrchestrator() {
        let config = activeConfig()
        AgentOrchestrator.companion.getInstance()
            .updateRemoteRuntimeConfig(remoteConfig: config, privacyLevel: nil)
    }

    /// 选中模型的显示名（供应商名 + 模型名）
    var selectedDisplayName: String {
        let config = activeConfig()
        let providerId = config.providerId
        let provider = RemoteModelConfig.companion.PROVIDERS
            .first { $0.providerId == providerId }
        let providerName = provider?.displayName ?? "PoLang Server"
        return "\(providerName) · \(config.modelId)"
    }
}
