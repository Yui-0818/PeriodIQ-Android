package io.github.yui0818.periodiq

import io.github.yui0818.periodiq.api.contract.ApiError

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数值字段的**逐字段**接受集哨兵（R9.1 的 REG-04，P2）。
 *
 * 对照依据不是"拿 confidence 的结论外推"，而是桌面 pydantic **本体实测**
 * （`.dsh/r9_1_probe/reg04_desktop_acceptance.jsonl`，588 条）。桌面侧声明
 * `ProfileUpdate.weight_kg: Optional[float] = Field(None, ge=25, le=300)`（`schemas/api.py:479`）。
 *
 * 下面每个用例都是报告 §3 那张"字段 / 桌面 / 安卓改前 / 安卓改后"四列表的一行；
 * 两侧结论**相同**的用例归前五条，**不同**的只有两条（NaN 与小数截断），各自单独成条。
 */
class NumericFieldAcceptanceTest {

    private fun body(value: Any?): JSONObject = JSONObject().apply {
        if (value == null) put("weight_kg", JSONObject.NULL) else put("weight_kg", value)
    }

    private fun weightText(value: Any?): String = profileWeightOrReject(body(value)).toString()

    /** 改前口径（只比区间、不提函数），返回"是否放行"；用来量出四列表里"安卓改前"那一列。 */
    private fun baselineOldRangePredicate(raw: Any?): Boolean {
        val v = JSONObject().put("weight_kg", raw).doubleOrNull("weight_kg") ?: return false
        return !(v < PiqConfig.WEIGHT_MIN_KG || v > PiqConfig.WEIGHT_MAX_KG)
    }

    // ———— 两端同判的形态 ————

    @Test
    fun `numeric strings are coerced on both ends because desktop pydantic runs in lax mode`() {
        assertEquals("70.5", weightText("70.5"))
        assertEquals("70.5", weightText(" 70.5 "))
        assertEquals("100.0", weightText("1e2"))
        assertEquals("28.0", weightText("+28"))
    }

    @Test
    fun `bool and empty string and containers are rejected on both ends`() {
        listOf<Any>(true, false, "", "abc", JSONObject(), JSONArray()).forEach { raw ->
            val err = runCatching { profileWeightOrReject(body(raw)) }.exceptionOrNull()
            assertTrue("$raw 必须被拒，实测=$err", err is ApiError && err.status == 422)
        }
    }

    @Test
    fun `null and absent key both mean leave the stored value untouched`() {
        assertNull(profileWeightOrReject(JSONObject().put("weight_kg", JSONObject.NULL)))
        assertNull(profileWeightOrReject(JSONObject()))
    }

    @Test
    fun `range bounds are inclusive and outside values get the range sentence`() {
        assertEquals("25.0", weightText(25.0))
        assertEquals("300.0", weightText(300.0))
        listOf(24.999, 300.001, -1.0, 0.0).forEach { raw ->
            val err = runCatching { profileWeightOrReject(body(raw)) }.exceptionOrNull()
            assertTrue("$raw 应被区间拒，实测=$err",
                err is ApiError && err.status == 422 && err.detail.contains("25 到 300"))
        }
    }

    @Test
    fun `hex form and overflow are rejected on both ends`() {
        listOf("0x10", "1e400").forEach { raw ->
            val err = runCatching { profileWeightOrReject(body(raw)) }.exceptionOrNull()
            assertTrue("$raw 必须被拒，实测=$err", err is ApiError && err.status == 422)
        }
    }

    // ———— 分叉一：非有限值（大小写敏感的浮点字面量语法）————

    @Test
    fun `nan spelling is what decides the android side while desktop rejects all of them`() {
        // 实测：Java/Kotlin 的浮点字面量语法大小写敏感
        assertNull("小写 \"nan\" 解析失败 ⇒ 无值", body("nan").doubleOrNull("weight_kg"))
        assertTrue("首字母大写的 \"NaN\" 会真的得到 NaN",
            body("NaN").doubleOrNull("weight_kg")?.isNaN() == true)
        assertEquals("大写 I 的 \"Infinity\" 会得到正无穷", "Infinity",
            body("Infinity").doubleOrNull("weight_kg").toString())
        // 桌面（pydantic）对 `"nan"`/`"NaN"`/`"Infinity"` 全部拒（`ge/le` 比较不过）
        listOf("nan", "NaN", "Infinity", "-Infinity", "inf").forEach { raw ->
            val err = runCatching { profileWeightOrReject(body(raw)) }.exceptionOrNull()
            assertTrue("$raw 必须被拒（桌面也拒它），实测=$err", err is ApiError && err.status == 422)
        }
    }

    @Test
    fun `the only form where old and new differ is exactly-case NaN`() {
        // 改前"只比区间"对 NaN 恒放行（NaN 与任何数比较都是 false）⇒ 这就是要补有限性的唯一理由
        // 注：这一条是**对照**（钉的是本文件 :30 自建的改前谓词，产品代码改坏它不会红）；产品判据是下一条
        assertTrue("改前口径放行 \"NaN\"（分叉的实测值）", baselineOldRangePredicate("NaN"))
        assertFalse("改后必须拒掉它（桌面也拒）", runCatching { profileWeightOrReject(body("NaN")) }.isSuccess)
        // 其余形态两侧判定逐项相同 ⇒ 本次提取重构没有改变任何结论
        listOf<Any>(70.5, 28, 0, 25.0, 300.0, 24.999, 300.001, -1.0,
            "70.5", " 70.5 ", "1e2", "+28", "0x10", "1e400", "nan", "Infinity", "inf", "", "abc", true, false,
        ).forEach { raw ->
            assertEquals("改前与改后必须同判：$raw",
                baselineOldRangePredicate(raw), runCatching { profileWeightOrReject(body(raw)) }.isSuccess)
        }
    }

    // ———— 分叉二：整数字段收到小数（安卓截断 / 桌面拒）————

    @Test
    fun `import rows coerce numeric strings but never invent a number from bool or garbage`() {
        val row = JSONObject()
            .put("cycle_length", "28")
            .put("period_days", "5")
            .put("error_days", true)
            .put("note", "")
        assertEquals("28", row.longOrNull("cycle_length").toString())
        assertEquals("5", row.longOrNull("period_days").toString())
        assertNull("bool 必须当无值（桌面靠手写 isinstance(x, bool) 拦，安卓同样不转）", row.longOrNull("error_days"))
        assertNull(row.doubleOrNull("note"))
    }

    @Test
    fun `fractional input to an integer column is truncated by android - registered divergence`() {
        val row = JSONObject().put("cycle_length", "28.7").put("period_days", 28.7)
        // 字符串小数：安卓不收（与桌面 int_parsing 同判）
        assertNull(row.longOrNull("cycle_length"))
        // ⚠ **数值**小数：`is Number -> raw.toLong()` 会**静默截断**成 28，
        // 桌面 pydantic 收 28.7 给 int 字段时报 `int_from_float` 拒。已登记 R9.1 报告 §6 由顾问裁定
        // （改 `longOrNull` 会影响十余个调用点与导入宽松度，不在收尾轮里动）。
        assertEquals("实测的截断行为（不是期望行为）", "28", row.longOrNull("period_days").toString())
        assertEquals("28", JSONObject().put("x", 28.0).longOrNull("x").toString())
        assertEquals("28.7", row.doubleOrNull("cycle_length").toString())
    }
}
