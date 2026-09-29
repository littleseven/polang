package com.mamba.picme.domain.tag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FaceClusterEngine] 合并/解散的命名保护单测（Robolectric + Room 内存库，真实 DAO）。
 *
 * 背景 bug（2026-09-29 用户反馈）：人物页给一组照片命名「大幂幂」后该组当场消失。
 * 根因：`mergeSmallClusters`/`dissolveSinks` 在维护开始时快照一次全量 person，
 * 整个凝聚式循环用**过期快照**判定命名保护；真正执行删除的 `mergeClusters`/`dissolveSinks`
 * 删除段提交前从不以 DB 现值复查。维护运行期间（大库可达数秒）用户命名 → 该组被按匿名
 * 判定合并/解散 → person 行被删 → 人物页分组消失（照片被吸收进其他簇，未丢失）。
 *
 * 本测试模拟「决策快照之后、删除提交之前用户完成命名」的竞态终点状态：
 * 直接以「B 已命名」的 DB 现值调用 `mergeClusters(a, b)`，断言 B 必须存活。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FaceClusterEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var engine: FaceClusterEngine

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = FaceClusterEngine(context, db.personDao(), db.mediaDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    /**
     * 造一个带 [embeddingCount] 条 embedding 的 person。
     * embedding 内容不参与合并决策（质心缓存未预热时本用例也不触发 matchCluster），全零即可。
     */
    private suspend fun seedPerson(name: String?, embeddingCount: Int): Long {
        val personId = db.personDao().insertPerson(PersonEntity(name = name, faceCount = embeddingCount))
        repeat(embeddingCount) { index ->
            db.personDao().insertEmbedding(
                FaceEmbeddingEntity(
                    mediaId = personId * 1000 + index,
                    personId = personId,
                    embedding = ByteArray(FaceClusterEngine.EMBEDDING_DIM * 4)
                )
            )
        }
        return personId
    }

    @Test
    fun `mergeClusters does not delete a cluster renamed after the merge decision snapshot`() = runTest {
        val survivor = seedPerson(name = null, embeddingCount = 3)
        val renamedMidMaintenance = seedPerson(name = "大幂幂", embeddingCount = 2)

        val merged = engine.mergeClusters(survivor, renamedMidMaintenance)

        assertFalse("已命名组不得被吸收，合并应被复查否决", merged)
        val person = db.personDao().getPerson(renamedMidMaintenance)
        assertNotNull("已命名组不得在合并提交时被删除", person)
        assertEquals("大幂幂", person?.name)
        assertEquals("已命名组的 embedding 不得被改派", 2, db.personDao().getEmbeddingCount(renamedMidMaintenance))
    }

    @Test
    fun `mergeClusters skips when both clusters are named`() = runTest {
        val a = seedPerson(name = "老郭", embeddingCount = 3)
        val b = seedPerson(name = "大幂幂", embeddingCount = 2)

        val merged = engine.mergeClusters(a, b)

        assertFalse("双方均已命名：合并应被跳过", merged)
        assertNotNull("双方均已命名：a 必须存活", db.personDao().getPerson(a))
        assertNotNull("双方均已命名：b 必须存活", db.personDao().getPerson(b))
    }

    @Test
    fun `mergeClusters still merges anonymous clusters`() = runTest {
        val survivor = seedPerson(name = null, embeddingCount = 3)
        val absorbed = seedPerson(name = null, embeddingCount = 2)

        val merged = engine.mergeClusters(survivor, absorbed)

        assertTrue("匿名组正常合并", merged)
        assertNull("匿名组正常被吸收删除", db.personDao().getPerson(absorbed))
        assertEquals("被吸收组的 embedding 全部改派到幸存组", 5, db.personDao().getEmbeddingCount(survivor))
    }
}
