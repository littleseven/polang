package com.mamba.picme.domain.person

/**
 * 人物查询解析结果
 *
 * @param personIds 解析出的去重人物 ID 集合（≥2 时调用方应走共现查询）
 * @param descriptions 人可读的来源说明（命中名字 / 称谓 → 人物），供日志与聊天回复引用
 * @param isAmbiguous 存在歧义：某称谓命中多条关系（已取并集）或同名命中多个人物
 * @param matchedTerms 命中人物的原词（自定义称呼 / 人名 / 亲属称谓），供调用方从
 *   内容关键词中消费，避免同一词再驱动标签搜索引入并集污染
 */
data class ResolvedPersons(
    val personIds: Set<Long>,
    val descriptions: List<String>,
    val isAmbiguous: Boolean,
    val matchedTerms: Set<String> = emptySet()
)

/**
 * 人物查询解析器 —— 原始查询字符串 → 人物 ID 集合
 *
 * 解析全部在端侧确定性完成（理由是确定性与查询性能），按优先级：
 * 1. 自定义称呼：query 包含某关系的 customLabel（"二儿子""发小"）→ 精确命中对应人物
 * 2. 已命名人物：query 包含某人名 → 命中
 * 3. 亲属称谓：[KinshipLexicon] 命中 → 经 [PersonRepository.resolveByKinship] 解到人物；
 *    一个称谓命中多条关系取并集并标记歧义。
 *    已被更长自定义称呼覆盖的称谓跳过（"二儿子"命中后不再用"儿子"取并集）
 * 4. "我"：仅在出现合拍电影 Pattern（"我和/和我/与我"）且已有其他人物命中时，
 *    才将本人计入（避免"我想看猫"这类第一人称查询误带本人照片）
 */
class PersonQueryResolver(
    private val personRepository: PersonRepository
) {

    suspend fun resolve(query: String): ResolvedPersons {
        if (query.isBlank()) {
            return ResolvedPersons(emptySet(), emptyList(), isAmbiguous = false)
        }

        val personIds = linkedSetOf<Long>()
        val descriptions = mutableListOf<String>()
        val matchedTerms = linkedSetOf<String>()
        var isAmbiguous = false

        // 1. 自定义称呼精确命中（最高优先级，多个不同称呼可同时命中）
        val customLabelHits = personRepository.resolveByCustomLabels(query)
        for (hit in customLabelHits) {
            personIds.add(hit.person.personId)
            matchedTerms.add(hit.label)
            descriptions.add("${hit.label} → ${hit.person.name ?: "#${hit.person.personId}"}")
        }

        // 2. 已命名人物命中（同名多人物取并集并标记歧义）
        val namedPersons = personRepository.getNamedPersons()
        val nameHits = namedPersons.filter { person ->
            val name = person.name
            !name.isNullOrBlank() && query.contains(name)
        }
        nameHits.groupBy { person -> person.name }.forEach { (name, persons) ->
            personIds.addAll(persons.map { person -> person.personId })
            if (!name.isNullOrBlank()) {
                matchedTerms.add(name)
            }
            if (persons.size > 1) {
                isAmbiguous = true
                descriptions.add("$name（同名 ${persons.size} 人，已取并集）")
            } else {
                descriptions.add(name.orEmpty())
            }
        }

        // 3. 亲属称谓命中（一词多关系取并集并标记歧义）；
        //    称谓已被命中的自定义称呼包含时跳过（避免"二儿子"又被"儿子"并集稀释）
        val matchedLabels = customLabelHits.map { hit -> hit.label }
        for ((term, _) in KinshipLexicon.scan(query)) {
            if (matchedLabels.any { label -> label.contains(term) }) continue
            var persons = personRepository.resolveByKinship(term)
            var viaClusterName = false
            if (persons.isEmpty()) {
                // 未声明该关系时兜底：称谓命中已命名人物分组名（如分组命名"大儿子"，查询"儿子"）
                persons = namedPersons.filter { person -> person.name?.contains(term) == true }
                viaClusterName = persons.isNotEmpty()
            }
            if (persons.isEmpty()) continue
            personIds.addAll(persons.map { person -> person.personId })
            matchedTerms.add(term)
            val names = persons.map { person -> person.name ?: "#${person.personId}" }
            when {
                persons.size > 1 -> {
                    isAmbiguous = true
                    descriptions.add("$term → ${names.joinToString("、")}（多人，已取并集）")
                }
                viaClusterName -> descriptions.add("$term → 命中分组名 ${names.first()}")
                else -> descriptions.add("$term → ${names.first()}")
            }
        }

        // 4. "我"：仅合拍 Pattern 且已有其他人物命中时计入
        if (personIds.isNotEmpty() && SELF_JOIN_PATTERNS.any { pattern -> query.contains(pattern) }) {
            val self = personRepository.getSelfPerson()
            if (self != null && self.personId !in personIds) {
                personIds.add(self.personId)
                descriptions.add("我")
            }
        }

        return ResolvedPersons(personIds, descriptions, isAmbiguous, matchedTerms)
    }

    companion object {
        /** 合拍意图 Pattern："X 和我/我和 X/与我" */
        private val SELF_JOIN_PATTERNS = listOf("我和", "和我", "与我", "我跟", "跟我")
    }
}
