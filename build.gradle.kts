// 工程级构建配置（R1 骨架）。版本号不在此声明 —— 由顾问独占（AGENTS.md §3.8）。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    // 可选支线 F（主稿第 6 步）：只验证 Chaquopy 能否构建 + 能否 import 纯 Python 模块
    id("com.chaquo.python") version "16.1.0" apply false
}
