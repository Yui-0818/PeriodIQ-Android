import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 可选支线 F：Chaquopy 可行性（不影响 Spike A–D；失败则整块回退）
    id("com.chaquo.python")
}

/**
 * 构建期 Python 解释器（R13.1 F13）。
 *
 * 解析顺序：Gradle 属性 `piq.python` → 环境变量 `PIQ_PYTHON` → 可读错误。
 * **不再硬编码宿主绝对路径，也不回退到 PATH 上的 `python` 存根**（那会把“没配置”
 * 伪装成“跑过了”）。解释器路径只在构建期使用，不进 APK。
 */
val pythonSetting = providers.gradleProperty("piq.python").orNull
    ?: System.getenv("PIQ_PYTHON")
val pythonFile: File = pythonSetting?.let(::File)
    ?: throw GradleException(
        "未配置 Python 解释器：请用 -Ppiq.python=<绝对路径> 或环境变量 PIQ_PYTHON 指定" +
            "（拒绝回退到 PATH 上的 python 存根）。",
    )
if (!pythonFile.isFile || !pythonFile.canRead()) {
    throw GradleException(
        "Python 解释器不可读：${pythonFile.path}。" +
            "请用 -Ppiq.python=<绝对路径> 或环境变量 PIQ_PYTHON 指定一个可执行文件。",
    )
}
val pythonExecutable = pythonFile.absolutePath

// ——— 发布件签名输入（R5 F2）———
// 查找顺序：① Gradle 属性 `piq.keystore.properties` ② 环境变量 `PIQ_KEYSTORE_PROPERTIES`
// ③ 仓库根 `keystore.properties`（该路径已被 `.gitignore` 覆盖，不入库）。
// keystore 与 properties 的正常落点都在**仓库外**（`AGENTS.md` §1.1 私有内容纪律），
// 所以这里不硬编码任何仓库外的绝对路径，由构建者把路径传进来。
//
// 硬判据：**请求 release 变体时找不到签名输入就必须报错**，
// 绝不允许"没有签名输入就用 debug 密钥签 release"（R5 提示词 F2）。
// 校验只在"这一轮会产出 release 变体"时做 ⇒ debug 链路（`clean` / `assembleDebug` /
// `testDebugUnitTest` / `installDebug`）不受"未配置发布签名"影响。
// ⚠ 两类都要算进来：① 显式带 Release 的任务名 ② **伞任务**（`assemble` / `build` / `check`）。
// 漏掉 ② 会被实测证伪：裸跑 `./gradlew assemble`（无签名输入）不报错，
// AGP 直接产出 `app/build/outputs/apk/release/app-release-unsigned.apk`（读数见 R5 报告 §6）。
val releaseTaskPattern = Regex("(?i)(assemble|bundle|install|package|sign|validate|lintVital)\\w*[Rr]elease")
val umbrellaTasks = setOf("assemble", "build", "check")
val releaseRequested = gradle.startParameter.taskNames.any { requested ->
    val simpleName = requested.substringAfterLast(':').removeSurrounding("'\"")
    releaseTaskPattern.containsMatchIn(requested) || simpleName.lowercase() in umbrellaTasks
}

val explicitKeystoreProps: String? =
    providers.gradleProperty("piq.keystore.properties").orNull ?: System.getenv("PIQ_KEYSTORE_PROPERTIES")
val keystorePropsFile: File? = explicitKeystoreProps?.let(::File)
    ?: rootProject.file("keystore.properties").takeIf { it.isFile }

if (explicitKeystoreProps != null && keystorePropsFile?.isFile != true) {
    throw GradleException(
        "release 签名输入不存在：显式指定了 '$explicitKeystoreProps'，不做静默回退。" +
            "改指正确的 keystore.properties，或删掉该属性/环境变量以使用仓库根的 keystore.properties。",
    )
}

val keystoreProps = Properties().apply {
    keystorePropsFile?.inputStream()?.use { load(it) }
}

/** 签名输入的四个必填字段（缺一即视为"输入不完整"，不做部分采用）。 */
val keystoreFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { keystoreProps.getProperty(it)?.trim() }

val keystoreReady = keystorePropsFile?.isFile == true &&
    keystoreFields.values.all { !it.isNullOrBlank() } &&
    File(keystoreFields["storeFile"].orEmpty()).isFile

if (releaseRequested && !keystoreReady) {
    val reasons = when {
        keystorePropsFile == null ->
            "三处查找路径都没有 keystore.properties：" +
                "① -Ppiq.keystore.properties=<路径> ② 环境变量 PIQ_KEYSTORE_PROPERTIES " +
                "③ 仓库根 keystore.properties（已被 .gitignore 覆盖）"
        !keystorePropsFile.isFile -> "keystore.properties 不存在：${keystorePropsFile.path}"
        keystoreFields.values.any { it.isNullOrBlank() } ->
            "keystore.properties（${keystorePropsFile.path}）缺字段：" +
                keystoreFields.filterValues { it.isNullOrBlank() }.keys.joinToString(", ")
        else -> "keystore.properties 指向的 keystore 文件不存在：${keystoreFields["storeFile"]}"
    }
    throw GradleException(
        "release 构建需要签名输入，但校验未通过 ⇒ $reasons。" +
            "禁止回退 debug 签名（AGENTS.md §5 / R5 提示词 F2）。",
    )
}

// ——— 发布物签名的第二道闸门：与"任务名怎么写"无关（R8 的 F-B-01，P1）———
// 上面那段靠 `gradle.startParameter.taskNames` 的**原始字符串**判断，天然漏检两类输入：
// Gradle 的 camel-case 缩写（`assembleR` / `aR`）在字符串匹配**之后**才被解析成 `assembleRelease`。
// R8 实测读数（L2-B，无 keystore、无环境变量）：
//   .\gradlew.bat assembleR → BUILD SUCCESSFUL + outputs/apk/release/app-release-unsigned.apk
//   .\gradlew.bat aR        → 同上
//   apksigner verify 该件   → DOES NOT VERIFY / ERROR: Missing META-INF/MANIFEST.MF
// 所以这里改成"任务真的在跑 release 变体的发布链时才断言"：拿的是**解析后的真实任务名**，
// 与用户怎么缩写无关。两道闸门并存：字符串那道给更早、更完整的报错；这道兜住漏检形态。
val releaseArtifactTask = Regex("(?i)^(assemble|bundle|package|signing|validate|install|lintVital)\\w*release")
tasks.matching { releaseArtifactTask.matches(it.name) }.configureEach {
    doFirst {
        if (!keystoreReady) {
            throw GradleException(
                "release 任务 `${this.name}` 在没有签名输入的情况下开始执行 ⇒ 已中止。" +
                    "禁止产出未签名/回退 debug 签名的 release 件（AGENTS.md §5）。" +
                    "请用 -Ppiq.keystore.properties=<路径> 或环境变量 PIQ_KEYSTORE_PROPERTIES 指定签名输入。",
            )
        }
    }
}

android {
    namespace = "io.github.yui0818.periodiq"
    compileSdk = 35

    signingConfigs {
        // 只在签名输入完整时创建；不完整时的报错由上面的 releaseRequested 校验负责。
        if (keystoreReady) {
            create("release") {
                storeFile = file(keystoreFields["storeFile"]!!)
                storePassword = keystoreFields["storePassword"]
                keyAlias = keystoreFields["keyAlias"]
                keyPassword = keystoreFields["keyPassword"]
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.yui0818.periodiq"
        minSdk = 26
        targetSdk = 35
        // 2.0.0 / 13：新前端、数据管理与通知收口后的首个开源发布版。
        versionName = "2.0.0"
        versionCode = 13
        ndk {
            // Chaquopy 要求显式 abiFilters（上一轮原始报错："Chaquopy requires ndk.abiFilters"）；
            // 真机是 arm64 设备，且本轮不打算跑其它 ABI（型号不写进仓库：R9.1 审计 P0）。
            abiFilters.add("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            // 签名走上面的 release signingConfig。缺签名输入时上面已报错，所以这里的 null
            // 只可能出现在"没请求 release 任务"的场合（例如同一次配置里跑 assembleDebug），
            // 绝不能让它变成"用 debug 密钥签 release"。
            signingConfig = signingConfigs.findByName("release")
            // 明令 false：本项目零第三方依赖、体积可接受，混淆会破坏"可读性"目标（AGENTS.md §3.2 书写纪律 3）
            // 并给 WebView 资产/JS 桥的反射路径引入无谓风险。开混淆需先给体积/启动/崩溃对照读数。
            isMinifyEnabled = false
            // 显式钉住：release 件**不可被调试**。注意 `MainActivity` 的 devtools socket
            // 挂在 `BuildConfig.DEBUG` 上（AGP 由 isDebuggable 生成该常量）⇒ 这条同时决定
            // release 件上没有 CDP 取证通道（R5 F3 的口径差异，见报告 §3/§6）。
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// Chaquopy 16.1.0 的正确 DSL 位置是顶层 chaquopy 扩展（[F4][F6] javap 实测：
// PythonPlugin 用 project.extensions.create("chaquopy", ...)；MIN_AGP_VERSION=7.0.0）。
// 只跑纯 Python 模块，不装任何第三方包（pydantic / fastapi 本轮明令不试）。
chaquopy {
    defaultConfig {
        // 快照算法用了 PEP 585（`list[dict]`）与 PEP 604（`str | None`）注解，
        // 它们在**导入期**求值 ⇒ 设备侧运行时必须 3.10+；取 3.11 与 HOST_PYTHON 同 minor，
        // 让 golden vectors 的桌面导出与真机取值不引入跨版本舍入差异。
        version = "3.11"
        buildPython(pythonExecutable)
    }
}

dependencies {
    // 只有 WebView 资产加载器；无网络库、无统计、无崩溃上报（AGENTS.md §5）
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    // JVM 单测里要用真的 org.json：android.jar 只提供会在调用时抛 "Stub!" 的桩类，
    // 而数据层/门禁的返回值形态（JSONObject/JSONArray）正是本轮要逐值核验的对象。
    // 仅 test 作用域 ⇒ 不进 APK，也不引入任何联网或统计能力。
    testImplementation("org.json:json:20240303")
}

// ——— 构建期前端同步（R14.1：唯一来源 = 仓库内 frontend/；无绕过开关）———
// 挂在 preBuild 之前：`clean` / `assembleDebug` 都会先清空 assets/www 再逐文件复制，
// 脚本自检 index.html / manifest.json，任一步不符即非零退出 ⇒ 构建失败。

val syncFrontendAssets = tasks.register<Exec>("syncFrontendAssets") {
    group = "build"
    description = "复制 frontend/ 前端源进 assets/www"
    workingDir = rootProject.projectDir
    // 不落 __pycache__：.gitignore 没有该规则，否则每次构建都会把工作区弄脏
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(pythonExecutable, "tools/copy_frontend.py")
}

// R13.1 F13：`app/src/main/python/app/` 是唯一 Python 源（8 文件入库），
// 不再从 `vendor/python` 复制，也不再核验 `docs/契约/python.sha256`。

// 构建期隐私硬闸门（AGENTS.md §5 第 1 条：命中即构建失败）。
// 扫描点（R8 的 F-B-02 / F-B-03 之后，四条各是什么）：
//   ① 源树 app/src/main/assets/     —— 前端副本 www/**
//   ② 源树 app/src/main/res/         —— 图标/主题/字符串
//   ③ 源树 app/src/main/python/      —— **本轮补**：Chaquopy 的打包输入根。此前不在扫描清单里，
//      实测落在该层的 `leak_probe.db` 与含全部备份键的 `.pyc` 会被原样收进
//      `assets/chaquopy/app.imy` 随包发布，而闸门报 `hits=0`（R8 L2-B 的种入读数）。
//   ④⑤ app/build/intermediates/assets/{debug,release} —— ⚠ **时序口径**：本任务由 `preBuild` 触发，
//      而 AGP 的 `merge*Assets` 在 `preBuild` **之后** ⇒ clean 构建时这两处是 `absent`（脚本按文档
//      "不参与判定"），非 clean 构建时读到的是**上一轮**的合并产物。原注释写成"将要打包的资产树"
//      与实测时序不符（R8 的 F-B-03，P2）。**"已经打包"级的复核由下面的 `privacyGatePackaged` 承担**
//      （REG-16/31，R9.1 落地）；这两点留在源树闸门里只是同一轮的重复扫描，不构成漏洞。
// 判据按"数据文件形态"（后缀 + 备份 JSON 字段组合 + zip 容器递归），无豁免名单、无绕过开关
// （tools/privacy_gate.py；只许加强，不许放松）。
// 排在两条 sync 之后：源树里的 www/ 是本轮刚复制进来的内容，闸门才看得见新产物。
val privacyGate = tasks.register<Exec>("privacyGate") {
    group = "build"
    description = "扫描源树资产/资源/Chaquopy 源目录与上一轮合并资产，命中用户数据即构建失败（AGENTS.md §5）"
    workingDir = rootProject.projectDir
    environment("PYTHONDONTWRITEBYTECODE", "1")
    // 两个变体的合并资产都扫：R5 起 release 件也是交付物，闸门必须在 release 构建链上同样成立。
    // 目录不存在时 `tools/privacy_gate.py` 只打印一行 `absent`（不参与判定），扫描点由本接线方负责。
    commandLine(
        pythonExecutable, "tools/privacy_gate.py",
        "app/src/main/assets", "app/src/main/res", "app/src/main/python",
        "app/build/intermediates/assets/debug", "app/build/intermediates/assets/release",
    )
    mustRunAfter(syncFrontendAssets)
}

tasks.named("preBuild") {
    dependsOn(syncFrontendAssets, privacyGate)
}

// ——— REG-16 / REG-31：打包**之后**的隐私复核（R9.1 落地）———
// 为什么必须再来一次：上面那条 `privacyGate` 挂在 `preBuild`，而 AGP 的 `merge*Assets` 与
// `package*` 都在 `preBuild` **之后** ⇒ clean 构建时它的两个"合并资产"扫描点是 `absent`（R8 的 F-B-03），
// 非 clean 构建时读到的是**上一轮**的合并产物。也就是说源树闸门从来没有看过"真正进包的东西"。
// 扫描点（三处，都是构建期产物，全部已被 `.gitignore` 覆盖）：
//   ①② app/build/intermediates/assets/{debug,release} —— merge*Assets 的**合并后资产树**，
//      本次构建刚生成的，不是上一轮的；
//   ③  app/build/outputs/apk —— **已打出来的 APK**。`tools/privacy_gate.py` 的判据 C 会把
//      zip 容器逐层拆开，所以这里等价于"拆开 `assets/chaquopy/app.imy` 再判内层"（R8 实测外层
//      压缩字节里看不见任何明文键 ⇒ 不拆就失明）。
// 接线方式：`assembleDebug` / `assembleRelease` / `bundleRelease` 的 **finalizer**。
// 选 finalizer 而不是 `dependsOn`，因为被终结的任务必须先产出 APK；finalizer 失败 ⇒ 整个构建失败，
// **没有** `ignoreFailures`、没有绕过开关（AGENTS.md §5 第 2 条）。
// 代价（R9.1 实测读数见报告 §3）：一次 APK 递归扫描的墙钟时间；正常构建不受影响、不会被跳过。
val privacyGatePackaged = tasks.register<Exec>("privacyGatePackaged") {
    group = "build"
    description = "打包后复核：扫合并后的资产树与已打出的 APK（含拆开 assets/chaquopy/app.imy 判内层）"
    workingDir = rootProject.projectDir
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(
        pythonExecutable, "tools/privacy_gate.py",
        "app/build/intermediates/assets/debug", "app/build/intermediates/assets/release",
        "app/build/outputs/apk",
    )
}

// 用 `matching{}` 按名字筛而不是 `named(...)`：AGP 的 assemble/bundle/package 任务在变体创建时才注册，
// 在 `android{}` 求值阶段 `named("assembleDebug")` 会抛 unknown task。
// 覆盖面（R9.1 的 L2-B finding B-03，P1）：除三个生命周期任务外**再加 `package*`** ——
// 直调 `./gradlew packageRelease` 会写出 `outputs/apk/release/app-release.apk` 而不经过 `assembleRelease`，
// 只挂三个名字等于留一条"直达打包、绕过后置复核"的路。
// ⚠ 缩写任务名**不是**绕过面（R9.1 实测 `./gradlew assembleR` 照样出现 `> Task :app:privacyGatePackaged`）：
// Gradle 在任务选择期就把 `aR`/`assembleR` 解析成同一个任务对象，`finalizedBy` 挂在对象上。
tasks.matching { t ->
    t.name in setOf("assembleDebug", "assembleRelease", "bundleRelease") || t.name.startsWith("package")
}.configureEach {
    finalizedBy(privacyGatePackaged)
}
