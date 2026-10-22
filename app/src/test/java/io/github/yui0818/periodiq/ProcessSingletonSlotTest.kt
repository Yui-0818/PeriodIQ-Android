package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.data.ProcessSlot

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程级单实例槽的哨兵（F-R10.2-01，R10.3）。
 *
 * 判据定义（四条各自会判红，逐条写明"怎么改才会红"）：
 *
 *  1. **复用**：槽被要第二次时必须还回**同一个对象**，第二个工厂一次都不许执行。
 *     变异 = 把 `ProcessSlot.get` 的"有则复用"改成"每次新建"（直接 `return factory()`）⇒ 本条红
 *     （`calls` 变 2、`assertSame` 变 `assertNotSame` 失败）。这是本轮唯一能钉住
 *     "Activity 重建不产生第二套库句柄"这条不变量的 **JVM** 手段 —— 句柄本身只能在设备上量
 *     （`SQLiteDatabase` 是 android.jar 桩类，JVM 里一调就抛 `Stub!`），读数见 R10.3 报告 §3。
 *  2. **并发只建一次**：16 个线程同时抢 ⇒ 工厂执行 1 次、16 个返回值全等。
 *     变异 = 去掉 `synchronized`（只留 `@Volatile` 检查）⇒ 本条红（实测会把工厂跑 2 次以上）。
 *  3. **接线**：`PiqServices` 必须经 `PiqDb.shared(` 取库；且 `PiqDb.kt` 之外的生产源码里
 *     不许出现 `PiqDb(` 构造点。变异 = 把 `PiqServices` 改回 `PiqDb(context)` ⇒ 本条红
 *     （构造器已 `private`，编译同样会拦；文本守卫的价值是**在编译前点名文件与行**）。
 *  4. **没有关闭入口**（恒等型，自曝）：`PiqDb` 不提供 `close()`，`MainActivity` 的生命周期里
 *     也不许出现关库调用。**当前它天然成立**（没有 `close()` 可写），所以它**不是**本轮的判据；
 *     它的红路径是将来有人新增 `fun close(` 或在 `onDestroy` 里关句柄 —— 那时本条立刻报红。
 *     按 `Role/执行端.md` §5.9 第 13 条，恒等型哨兵必须这样写明。
 *
 * ⚠ 本类不碰数据库：设备侧三条读数（重建后句柄回到基线 / fd 波动 / `db-open` 计数）
 * 由 `.dsh/r10_3_probe/` 的探针在模拟器 `piq35` 上取，见报告 §3。
 */
class ProcessSingletonSlotTest {

    @Test
    fun `slot reuses the first instance and never runs a second factory`() {
        val slot = ProcessSlot<Any>()
        val calls = AtomicInteger()
        val first = Object()
        val second = Object()

        val a = slot.get { calls.incrementAndGet(); first }
        val b = slot.get { calls.incrementAndGet(); second }

        assertEquals("槽只该建一次", 1, calls.get())
        assertSame("第二次取到的是另一个对象 ⇒ 句柄会成倍增加", a, b)
        assertSame(first, a)
        assertNotSame(second, a)
    }

    @Test
    fun `concurrent callers get one instance built exactly once`() {
        val slot = ProcessSlot<Any>()
        val calls = AtomicInteger()
        val threads = 16
        val started = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<Any> {
                    started.countDown()
                    go.await(5, TimeUnit.SECONDS)
                    slot.get { calls.incrementAndGet(); Any() }
                }
            }
            assertTrue("线程没起来，本条没测到东西", started.await(5, TimeUnit.SECONDS))
            go.countDown()
            val got = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals("并发下多建了实例", 1, calls.get())
            got.forEach { assertSame(got.first(), it) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `production wiring obtains the store through the process level accessor`() {
        val services = sourceOf("PiqServices.kt")
        assertTrue("PiqServices 没走进程级入口", services.contains("PiqDb.shared("))

        val dbFile = sourceOf("PiqDb.kt")
        assertTrue("PiqDb 构造器没封成私有 ⇒ 任何人都能再建一套句柄",
            Regex("""class\s+PiqDb\s+private\s+constructor""").containsMatchIn(dbFile))

        val constructorsOutsideDb = mainSources()
            .filter { (name, _) -> name != "PiqDb.kt" }
            .filter { (_, text) -> Regex("""\bPiqDb\s*\(""").containsMatchIn(text) }
            .map { (name, _) -> name }
        assertEquals("PiqDb.kt 之外出现构造点", emptyList<String>(), constructorsOutsideDb)
    }

    @Test
    fun `nothing in production can close the shared handle`() {
        val dbFile = sourceOf("PiqDb.kt")
        val activity = sourceOf("MainActivity.kt")
        assertFalse("PiqDb 新增了 close 入口", Regex("""fun\s+close\s*\(""").containsMatchIn(dbFile))
        assertFalse("Activity 生命周期里关库", Regex("""\.(close|closeQuietly)\s*\(""").containsMatchIn(activity))
        assertTrue("PiqDb 不再暴露 handle ⇒ 本条守卫的扫描前提变了",
            dbFile.contains("val handle: SQLiteDatabase"))
    }

    /**
     * 生产源码原文。找不到文件就**抛红**而不是返回空串 —— 空串会让下面所有 `contains` 判成
     * "没命中所以没有"，那是恒真守卫（`Role/执行端.md` §5.9 第 8/11 条）。
     */
    private fun sourceOf(fileName: String): String =
        mainSources().firstOrNull { (name, _) -> name == fileName }?.second
            ?: throw IllegalStateException("生产源码里找不到 $fileName，源守卫已失效")

    private val sources: List<Pair<String, String>> by lazy {
        mainSourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }

    private fun mainSources(): List<Pair<String, String>> = sources

    /** 单测工作目录由构建工具决定（AGP 给模块目录），因此向上找 —— 与 `ImportNoteLimitTruthTest` 同法。 */
    private fun mainSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (rel in listOf("src/main/java", "app/src/main/java")) {
                val candidate = File(dir, rel)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("未找到生产源码目录，源守卫无法执行（cwd=${System.getProperty("user.dir")}）")
    }
}
