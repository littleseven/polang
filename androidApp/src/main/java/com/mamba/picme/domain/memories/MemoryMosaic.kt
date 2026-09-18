package com.mamba.picme.domain.memories

/**
 * 回忆详情页蒙德里安拼贴布局（纯函数，JVM 可测）。
 * spec：`docs/08-UI-SPECS/screens/memories.yaml` §3 detail_page.grid。
 *
 * 五种卡块按 memory.id 种子确定性洗牌排布，同向（左/右）不相邻：
 * 同 id 同 URI 集输出恒定；媒体增删导致集合变化时序列自然顺延、不承诺稳定。
 * 尾行不足（剩余 ≤ 3 项）一律按方卡行补齐。
 */

/** 卡块朝向（同向不相邻约束的判定维度；SQUARE_ROW 为中性可与任意相邻） */
enum class MosaicOrientation { LEFT, RIGHT, NONE }

/** 五种卡块：2×2 大卡块（左/右）、2×1 横幅块（左/右）、三方卡行 */
enum class MosaicBlockType(val capacity: Int, val orientation: MosaicOrientation) {
    BIG_LEFT(capacity = 3, orientation = MosaicOrientation.LEFT),
    BIG_RIGHT(capacity = 3, orientation = MosaicOrientation.RIGHT),
    BANNER_LEFT(capacity = 2, orientation = MosaicOrientation.LEFT),
    BANNER_RIGHT(capacity = 2, orientation = MosaicOrientation.RIGHT),
    SQUARE_ROW(capacity = 3, orientation = MosaicOrientation.NONE),
}

/** 一个卡块 = 类型 + 该块消费的 URI（保持精选序） */
data class MosaicBlock(val type: MosaicBlockType, val uris: List<String>)

object MemoryMosaic {

    private const val MAX_REDRAW = 3
    private const val FNV_OFFSET_BASIS = 0x811C9DC5u
    private const val FNV_PRIME = 0x01000193u
    private const val NONZERO_SEED_FALLBACK = 0x9E3779B9u

    /**
     * 计算拼贴卡块序列。[uris] 按展示序（精选 = 美学分降序截 12 / 全部 = 时间降序）传入，
     * 各卡块依序消费；每块首项落 2×2 大卡位 / 2×1 横幅位。
     */
    fun layout(memoryId: String, uris: List<String>): List<MosaicBlock> {
        if (uris.isEmpty()) return emptyList()
        var state = fnv1a32(memoryId).let { seed -> if (seed == 0u) NONZERO_SEED_FALLBACK else seed }
        val blocks = mutableListOf<MosaicBlock>()
        var offset = 0
        var prevOrientation = MosaicOrientation.NONE
        while (offset < uris.size) {
            val remaining = uris.size - offset
            val chosen = if (remaining <= MosaicBlockType.SQUARE_ROW.capacity) {
                // 尾行不足：方卡行补齐，不再抽大卡/横幅
                MosaicBlockType.SQUARE_ROW
            } else {
                var accepted: MosaicBlockType? = null
                var attempts = 0
                while (accepted == null && attempts < MAX_REDRAW) {
                    state = xorshift32(state)
                    val candidate = MosaicBlockType.entries[
                        (state % MosaicBlockType.entries.size.toUInt()).toInt()
                    ]
                    attempts++
                    if (candidate.orientation == MosaicOrientation.NONE ||
                        candidate.orientation != prevOrientation
                    ) {
                        accepted = candidate
                    }
                }
                accepted ?: MosaicBlockType.SQUARE_ROW
            }
            val take = minOf(chosen.capacity, remaining)
            blocks += MosaicBlock(chosen, uris.subList(offset, offset + take))
            prevOrientation = chosen.orientation
            offset += take
        }
        return blocks
    }

    /** FNV-1a 32bit 稳定哈希（memory.id 字符串 → 洗牌种子） */
    internal fun fnv1a32(text: String): UInt {
        var hash = FNV_OFFSET_BASIS
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF).toUInt()
            hash *= FNV_PRIME
        }
        return hash
    }

    /** xorshift32 PRNG 单步 */
    internal fun xorshift32(state: UInt): UInt {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x shr 17)
        x = x xor (x shl 5)
        return x
    }
}
