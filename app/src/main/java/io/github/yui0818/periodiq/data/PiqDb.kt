package io.github.yui0818.periodiq.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.github.yui0818.periodiq.PiqConfig
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONObject

/**
 * 本机 SQLite 数据层（R3 F1）。
 *
 * **表定义与写锁语义逐字移植桌面 `backend/app/db/database.py`**。之所以移植而不是复用
 * 桌面 Python 实现：`database.py` 依赖 `aiosqlite`（799 行 / 133 处 `await`），进不了
 * Chaquopy（`docs/提示词/R3` §1 已由顾问实测订正）。
 * - DDL 取 database.py 的 v3 终形（`_TBL_*` 常量），不含 v1→v2→v3 迁移步骤：
 *   安卓不存在存量旧库，迁移链在"首启空库"前提下没有可执行对象；
 * - `journal_mode=WAL` + `foreign_keys=ON` 同 `get_connection()`；
 * - 写路径串行 = 桌面 `WriteLock` 的语义等价物（一把锁 + 一个事务，异常整体回滚）。
 *
 * 落点在应用私有目录 `filesDir/data/app.db`（与桌面 `backend/data/app.db` 同构的相对布局）：
 * 不在 `assets/` 与 `res/` 内 ⇒ 不进 APK，`AGENTS.md` §5 第 1 条的打包闸门不受影响。
 *
 * ⚠ **实例数 = 1 / 进程**（F-R10.2-01，R10.3 修）：构造器是 `private`，唯一入口是 [shared]。
 * 理由不是"省句柄"，而是**正确性**：[writeLock] 与 [handle] 都是实例级的，所以"单写者"这条
 * 不变量只有在全进程共用一个实例时才真的成立。改字号/语言/密度会让 `MainActivity` 重建
 * （`AndroidManifest` 的 `configChanges` 不含 `fontScale|locale|density`），R10.2 实测每次重建
 * 都多留一套 `app.db`/`-wal` 句柄、5 个实例并存无一关闭 ⇒ 那几把锁各锁各的。
 * 相应地，这里**没有** `close()`：库句柄的生命周期跟进程一致，**不许**在 Activity 生命周期里关它
 * （`onDestroy` 不一定被调用，把它当回收点会重新引入"实例并存"）。
 *
 * ⚠⚠ **改这里的 DDL 之前先读 `docs/契约/跨端契约.md` §13**（R-ARCH-1）：
 * 本类没有 `SQLiteOpenHelper`、没有 `DATABASE_VERSION`、没有 `onUpgrade`，
 * 而 `CREATE TABLE IF NOT EXISTS` 对**已存在的表是 no-op** ⇒
 * **DDL 文本的改动只对"全新安装"生效**，老库的列默认值与约束永远停在它被建立时的文本上。
 * 也就是说：改 `PiqConfig.PRIOR_*` / `MIN|MAX_CYCLE_DAYS` 会同时改"新装用户的建库文本"和
 * "老用户库里那个没跟着动的旧文本"，`SchemaDefaultTextTest` 钉得住前者、钉不住后者。
 * 真要改表结构（加列、改约束）⇒ **唯一可行路径 = 导出 JSON → 重装 → 导入**（契约 §13 的决策：
 * 单机私有应用、数据可完整往返，暂不建迁移层）。
 */
class PiqDb private constructor(context: Context) {

    private val file: File = File(File(context.filesDir, DIR_DATA), FILE_DB)

    /** 写串行锁（桌面 WriteLock 的等价物：单写者、事务内多语句原子）。 */
    val writeLock = ReentrantLock()

    val handle: SQLiteDatabase = run {
        file.parentFile?.mkdirs()
        val created = SQLiteDatabase.openOrCreateDatabase(file, null)
        // `PRAGMA journal_mode=WAL` 会**返回一行**（新模式），Android 的 execSQL 对返回数据的语句
        // 直接抛 "Queries can be performed using SQLiteDatabase query or rawQuery methods only"
        // （真机实测）⇒ 用 rawQuery 执行，并顺手把返回的模式读成日志留证。
        val walMode = created.rawQuery("PRAGMA journal_mode=WAL", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else "unreadable"
        }
        created.execSQL("PRAGMA foreign_keys=ON")
        DDL.forEach { created.execSQL(it) }
        created.execSQL(SQL_INDEX_CYCLES_USER)
        created.execSQL(SQL_INDEX_PRED_USER)
        Log.i(
            TAG,
            "db-open|path=${file.path}|bytes=${file.length()}|journal=$walMode|fk=on|ddl=${DDL.size}",
        )
        created
    }

    /** 写事务（与桌面 `async with WriteLock()` 同语义）。 */
    fun <T> write(block: (SQLiteDatabase) -> T): T {
        writeLock.lock()
        try {
            handle.beginTransaction()
            try {
                val result = block(handle)
                handle.setTransactionSuccessful()
                return result
            } finally {
                handle.endTransaction()
            }
        } finally {
            writeLock.unlock()
        }
    }

    companion object {

        private const val TAG = "PIQ"
        private const val DIR_DATA = "data"
        private const val FILE_DB = "app.db"

        /** 进程级单实例槽（[shared] 用它；见 `ProcessSlot` 的抽取理由）。 */
        private val slot = ProcessSlot<PiqDb>()

        /**
         * 取**全进程唯一的** `PiqDb`：第一次调用建，之后每次（含 `MainActivity` 重建后的第二个
         * `PiqServices`）都复用同一个实例 —— 也就是同一把 [writeLock] 和同一组库句柄。
         *
         * `applicationContext` 只用于取 `filesDir`，而它在进程内恒定 ⇒ 单槽就够，不需要按路径分槽。
         * 调用线程仍只能是工作线程（JS 桥 / WebView 资源拦截），构造期会建表 —— 与 [PiqServices]
         * 的"不在主线程建库"纪律一致；槽的锁只保护"建不建"这一步，不在库操作路径上。
         */
        fun shared(context: Context): PiqDb = slot.get { PiqDb(context.applicationContext) }

        /** 与 database.py `_TBL_SESSIONS` 同源。 */
        private const val TBL_SESSIONS = """(
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP
)"""

        private const val TBL_CYCLES = """(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE,
    cycle_length INTEGER,
    period_days INTEGER,
    note TEXT DEFAULT '',
    is_anomalous BOOLEAN DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_date CHECK (period_end IS NULL OR period_end >= period_start),
    CONSTRAINT chk_length CHECK (cycle_length IS NULL OR (cycle_length >= ${PiqConfig.MIN_CYCLE_DAYS} AND cycle_length <= ${PiqConfig.MAX_CYCLE_DAYS}))
)"""

        private const val TBL_PREDICTIONS = """(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    predicted_date DATE NOT NULL,
    predicted_start_upper DATE NOT NULL,
    predicted_start_lower DATE NOT NULL,
    confidence REAL NOT NULL,
    model_version TEXT NOT NULL,
    model_params_json TEXT NOT NULL,
    actual_date DATE,
    error_days INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)"""

        private const val TBL_MODEL_PARAMS = """(
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    mean_cycle REAL NOT NULL DEFAULT ${PiqConfig.PRIOR_MEAN},
    std_cycle REAL NOT NULL DEFAULT ${PiqConfig.PRIOR_STD},
    n_records INTEGER NOT NULL DEFAULT 0,
    ema_cycle REAL NOT NULL DEFAULT ${PiqConfig.PRIOR_MEAN},
    prior_mean REAL NOT NULL DEFAULT ${PiqConfig.PRIOR_MEAN},
    prior_std REAL NOT NULL DEFAULT ${PiqConfig.PRIOR_STD},
    prior_weight REAL NOT NULL DEFAULT ${PiqConfig.PRIOR_WEIGHT},
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)"""

        /** R1-A 空壳形：业务列已全部移除（database.py 注释）。 */
        private const val TBL_APP_SETTINGS = """(
    user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)"""

        private const val SQL_INDEX_CYCLES_USER =
            "CREATE INDEX IF NOT EXISTS idx_cycles_user ON cycles(user_id, period_start DESC)"
        private const val SQL_INDEX_PRED_USER =
            "CREATE INDEX IF NOT EXISTS idx_pred_user ON predictions(user_id, created_at DESC)"

        /** 与 database.py `DDL_STATEMENTS` 逐条同序。 */
        val DDL: List<String> = listOf(
            """
    CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        nickname TEXT,
        avatar TEXT,
        birthday TEXT,
        weight_kg REAL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
            """.trimIndent(),
            "CREATE TABLE IF NOT EXISTS sessions $TBL_SESSIONS",
            "CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)",
            "CREATE TABLE IF NOT EXISTS cycles $TBL_CYCLES",
            "CREATE INDEX IF NOT EXISTS idx_cycles_start ON cycles(period_start DESC)",
            "CREATE TABLE IF NOT EXISTS predictions $TBL_PREDICTIONS",
            "CREATE INDEX IF NOT EXISTS idx_pred_date ON predictions(created_at DESC)",
            "CREATE TABLE IF NOT EXISTS model_params $TBL_MODEL_PARAMS",
            "CREATE TABLE IF NOT EXISTS app_settings $TBL_APP_SETTINGS",
        )

        /** 列名与顺序取自 database.py `_migrate_v3_governance` 的逐表列清单（导出行序用它）。 */
        val CYCLE_COLUMNS = listOf(
            "id", "user_id", "period_start", "period_end", "cycle_length",
            "period_days", "note", "is_anomalous", "created_at",
        )
        val PREDICTION_COLUMNS = listOf(
            "id", "user_id", "predicted_date", "predicted_start_upper", "predicted_start_lower",
            "confidence", "model_version", "model_params_json", "actual_date", "error_days",
            "created_at",
        )
        val MODEL_PARAMS_COLUMNS = listOf(
            "user_id", "mean_cycle", "std_cycle", "n_records", "ema_cycle",
            "prior_mean", "prior_std", "prior_weight", "updated_at",
        )
        val USER_COLUMNS = listOf(
            "id", "username", "password_hash", "nickname", "avatar", "birthday",
            "weight_kg", "created_at",
        )
    }
}

/**
 * 进程级单实例槽：**第一次** [get] 用工厂建实例，之后一律复用同一个（工厂再传几次都不再建）。
 *
 * 单独成一个类只为一件事 —— 让"复用"这条不变量能在 **JVM 单测里被直接驱动**：`SQLiteDatabase`
 * 是 android.jar 的桩类（`app/build.gradle.kts` 的 test 依赖只有 junit + org.json，没有 Robolectric），
 * 所以"进程级单实例"若只写在 `PiqDb` 的构造路径上，JVM 侧就只能靠读源码文本自证。
 * 这里把槽与 Android API 解耦，哨兵用一个纯 Kotlin 工厂即可逐值验（`ProcessSingletonSlotTest`）。
 *
 * 线程语义：双重检查 + `@Volatile` ⇒ 工厂至多执行一次，并发调用者拿到同一个实例。
 */
class ProcessSlot<T : Any> {

    private val lock = Any()

    @Volatile
    private var instance: T? = null

    fun get(factory: () -> T): T {
        instance?.let { return it }
        return synchronized(lock) {
            instance?.let { return it }
            factory().also { instance = it }
        }
    }
}

/** 游标当前行 → `JSONObject`（键 = 列名，NULL → JSON null；与桌面 `dict(row)` 同形）。 */
fun Cursor.toRowJson(columns: List<String>): JSONObject {
    val json = JSONObject()
    for (name in columns) {
        val idx = getColumnIndex(name)
        if (idx < 0) continue
        json.put(name, when (getType(idx)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> getLong(idx)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(idx)
            Cursor.FIELD_TYPE_BLOB -> JSONObject.NULL
            else -> getString(idx) ?: JSONObject.NULL
        })
    }
    return json
}
