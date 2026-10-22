r"""构建期隐私硬闸门（`AGENTS.md` §5 第 1 条：命中即**构建失败**，不是事后取证）。

判据显式化（`AGENTS.md` §3.4 第 4 道闸门 —— "什么算命中"写在这里，也写进 R2-c 报告 §3）：
一个文件被判为**用户数据**，当且仅当它满足下列任一条：

A. **数据文件后缀**：文件名以 `.db` / `.db-wal` / `.db-shm` / `.db-journal` /
   `.sqlite` / `.sqlite3` / `.bak` 结尾（大小写不敏感）。
B. **备份键组合**（跨端契约 §1 的两种形态，同一条组合的三个键**同时出现**才算）：
   #1 `"period_start"` + `"predicted_date"` + `"model_params"`（逐条记录形态）
   #2 `"cycles"` + `"predictions"` + `"exported_at"`（`GET /export` 备份信封形态）
   组合在**文件内容**上判定，流式扫描，**与后缀无关、没有体积上限** ——
   备份被改名成 `.txt` / 去掉后缀 / 放大到几十 MB 都仍在覆盖面内（R2-b 报告 §6-15/§6-16 的两个洞）。
C. **容器递归**（R8 加）：zip 魔数（`PK`）开头的文件——`assets/chaquopy/*.imy`、APK 本身、
   `.jar`/`.zip`——**拆开后对每一条内层条目再跑 A/B 两条判据**（可嵌套）。
   原因：`.imy` 是压缩的嵌套 zip，外层字节里看不见任何明文键（R8 实测
   `keys_visible_in_compressed_imy_bytes=[]`，同容器解开后内层却有 `leak_probe.db` 与含全部备份键的
   `.pyc`）⇒ 不拆就对"唯一会装算法代码的容器"失明。
   拆开失败**既留可见读数、又按命中处理**：打印 `WARN|container-unparsable|…` 或
   `WARN|container-entry-unreadable|…` 的同时把该文件/该条目计入 `HIT`（判据 D 同口径）。
   理由：一个自称是 zip 却解析不了的文件，"读不到"不等于"干净"；只打 WARN 不报红会重演
   "扫过了"与"根本没扫"在退出码上不可分辨那个洞。
D. **判定无法完成**：`stat` 或 `open` 失败 ⇒ **按命中处理**。读不到不等于干净，
   静默放过会让"扫过了"与"根本没扫"在退出码上不可分辨。

**没有静默放过的路径**：每条文件要么给出"未命中"（不打印，因为 SCAN 行的 scanned 计数
就是它的读数），要么给出 `HIT|` 或 `WARN|` 行；超过 8 MiB 的文件额外打印一行
`LARGE|<path>|size=<n>|content-judged=yes`，把曾经"超限即跳过"的边界变成可读数。

**为什么这样判不会误报 Chaquopy 产物**（R1 实测、`AGENTS.md` §5 第 1 条明文要求）：
- `lib/arm64-v8a/libsqlite3_*.so` 后缀是 `.so` ⇒ A 不匹配；内容里不含任一组备份键 ⇒ B 不匹配；
- `assets/chaquopy/build.json` 是 Chaquopy 自己的资产 sha1 清单，不含上述任一组键组合
  ⇒ B 不匹配（R2-c 复核：真实资产树 500 文件 / 34,653,284 B 全量流式扫描 0 命中）。
⇒ 本脚本**没有任何豁免名单**，也就没有可被绕过的口子。

**无绕过开关**：不接受 `--allow-data` / `--force` / 环境变量豁免 / `ignoreFailures`；
命中即退出码 1。单个目录不存在只记一行 `absent`、**不参与命中判定**（实测：三目标里一个缺席、
两个扫到且干净 ⇒ `GATE|PASS`、退出码 0）—— 扫描点是否该存在由接线方（`app/build.gradle.kts`）负责，
本脚本不为它开豁免。但**所有目标都没扫到**时不是"干净"而是"没扫"，见下一条退出码口径。

用法：`python tools/privacy_gate.py <相对目录> [...]`（目录相对当前工作目录，递归扫描）
退出码：0 = 扫到了文件且无命中；1 = 有命中 / 参数非法 / **一个文件都没扫到**
（R9.1 的 B-02：缺席的扫描点不等于干净，"没扫"必须与"扫过且干净"在退出码上可分辨）。
"""
from __future__ import annotations

import io
import os
import re
import sys
import zipfile

DATA_SUFFIX = re.compile(r"\.(db|db-wal|db-shm|db-journal|sqlite|sqlite3|bak)$", re.I)
BACKUP_KEYSETS = (
    (b'"period_start"', b'"predicted_date"', b'"model_params"'),
    (b'"cycles"', b'"predictions"', b'"exported_at"'),
)
BACKUP_KEYSET_NAMES = (
    "period_start+predicted_date+model_params",
    "cycles+predictions+exported_at",
)
# 只用于打印可读边界读数（判据 B 不受它影响）：曾经这里是"超限即 continue"的静默豁免。
LARGE_BYTES = 8 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024
MAX_KEY_BYTES = max(len(k) for keys in BACKUP_KEYSETS for k in keys)


def match_keysets(stream) -> int | None:
    """流式扫描：返回命中的组合序号，未命中返回 None。

    组合语义与修复前一致 = **每个键在文件任意位置出现**即可（备份信封里
    `"cycles"` 在开头、`"exported_at"` 在结尾，相距可达几十 MB），所以逐键记状态而不是
    在同一个窗口里找三个键；跨块边界的单个键用 `MAX_KEY_BYTES - 1` 的重叠窗口兜住。
    """
    seen = [set(), set()]
    tail = b""
    while True:
        chunk = stream.read(CHUNK_BYTES)
        if not chunk:
            break
        buf = tail + chunk
        for i, keys in enumerate(BACKUP_KEYSETS):
            for j, key in enumerate(keys):
                if key in buf:
                    seen[i].add(j)
            if len(seen[i]) == len(keys):
                return i
        tail = buf[-(MAX_KEY_BYTES - 1):]
    for i, keys in enumerate(BACKUP_KEYSETS):
        if len(seen[i]) == len(keys):
            return i
    return None


def classify_bytes(name: str, blob: bytes) -> str | None:
    """对**内存里的一段字节**按 A/B 两条判据判定（容器内层用它，不落临时文件）。"""
    if DATA_SUFFIX.search(name):
        return "数据文件后缀(容器内)"
    for i, keys in enumerate(BACKUP_KEYSETS):
        if all(key in blob for key in keys):
            return "备份键组合#%d(%s),容器内,size=%d" % (i + 1, BACKUP_KEYSET_NAMES[i], len(blob))
    return None


def scan_container(path: str, blob: bytes) -> list[str]:
    """把 zip 容器（`.imy` / `.apk` / `.jar` / `.zip`）拆开后逐条判定，返回命中清单。

    为什么必须拆：`assets/chaquopy/app.imy` 是**压缩的嵌套 zip**，A/B 两条判据都只能看
    "文件名"与"原始字节"，而压缩后的外层字节里没有任何明文键
    （R8 实测：`keys_visible_in_compressed_imy_bytes=[]`，同容器解开后内层却装着
    `leak_probe.db` 与含全部备份键的 `.pyc`）⇒ 不拆就等于对唯一会装算法代码的容器失明。

    打不开 ⇒ **按命中处理**（与判据 D 同口径）：一个自称是 zip 却解析不了的文件，
    等价于"读不到"，而"读不到"不等于干净；只打 `WARN|` 不报红会重演
    "扫过了"与"根本没扫"在退出码上不可分辨那个洞。
    """
    if not blob[:2] == b"PK":
        return []
    hits: list[str] = []
    try:
        with zipfile.ZipFile(io.BytesIO(blob)) as inner:
            for entry in inner.infolist():
                if entry.is_dir():
                    continue
                try:
                    data = inner.read(entry)
                except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
                    print("WARN|container-entry-unreadable|%s!%s|%s" % (
                        path.replace(os.sep, "/"), entry.filename, exc))
                    hits.append("%s!%s|容器条目读不出⇒按命中处理" % (
                        path.replace(os.sep, "/"), entry.filename))
                    continue
                reason = classify_bytes(entry.filename, data)
                if reason:
                    hits.append("%s!%s|%s" % (path.replace(os.sep, "/"), entry.filename, reason))
                # 容器套容器（APK 里就有 .imy）：再往里拆一层，深度由格式天然限制
                if data[:2] == b"PK":
                    hits.extend("%s<-%s" % (path.replace(os.sep, "/"), h)
                                for h in scan_container(entry.filename, data))
    except (zipfile.BadZipFile, OSError, RuntimeError) as exc:
        print("WARN|container-unparsable|%s|%s" % (path.replace(os.sep, "/"), exc))
        return hits + ["%s|容器自称 zip 却解析不了⇒按命中处理" % path.replace(os.sep, "/")]
    return hits


def classify(path: str, name: str) -> tuple[str | None, list[str]]:
    """返回 (命中原因或 None, 容器内层命中清单)。

    A/B 两条主判据不变；容器（zip 魔数）额外拆开判内层——见 `scan_container` 的说明。
    """
    if DATA_SUFFIX.search(name):
        return "数据文件后缀", []
    try:
        size = os.path.getsize(path)
    except OSError as exc:
        return "判定无法完成(stat:%s)⇒按命中处理" % exc, []
    if size > LARGE_BYTES:
        print("LARGE|%s|size=%d|content-judged=yes" % (path.replace(os.sep, "/"), size))
    try:
        with open(path, "rb") as fh:
            head = fh.read(2)
            if head == b"PK":
                fh.seek(0)
                blob = fh.read()
                return None, scan_container(path, blob)
            # PK 探测读掉了开头 2 字节；不倒回会把 `{"cycles"...` 的首个键切断（R13.1 F6 探针实测）。
            fh.seek(0)
            hit = match_keysets(fh)
    except OSError as exc:
        return "判定无法完成(open:%s)⇒按命中处理" % exc, []
    if hit is None:
        return None, []
    return "备份键组合#%d(%s),size=%d" % (hit + 1, BACKUP_KEYSET_NAMES[hit], size), []


def classify_link(
    path: str,
    name: str,
    link_check=os.path.islink,
    resolve=os.path.realpath,
) -> tuple[str | None, list[str]]:
    """符号链接**不再静默跳过**（R13.1 F6 / R12-P2-06）。

    判据：链接名或解析后的目标名命中数据后缀 ⇒ HIT；目标不可读/是目录 ⇒ HIT
    （与判据 D 同口径）；目标是普通文件 ⇒ 按目标内容跑同一套 A/B/C 判据。
    `link_check` / `resolve` 可注入，供 Windows 无建链接权限时做阳性/阴性对照。
    """
    if not link_check(path):
        return None, []
    target = resolve(path)
    target_name = os.path.basename(target)
    if DATA_SUFFIX.search(name) or DATA_SUFFIX.search(target_name):
        return "符号链接→数据文件后缀(target=%s)" % target_name, []
    if not os.path.isfile(target):
        return "符号链接目标不可读⇒按命中处理", []
    return classify(target, target_name)


def scan_dir(root: str) -> tuple[int, int, list[str]]:
    """返回 (扫描文件数, 命中数, 命中清单)。目录不存在返回 (-1, 0, [])。"""
    if not os.path.isdir(root):
        return -1, 0, []
    scanned = 0
    hits: list[str] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        # 符号链接目录不 follow，但也不能静默跳过：按一个条目计入 scanned 并判 HIT。
        for dirname in list(dirnames):
            path = os.path.join(dirpath, dirname)
            if not os.path.islink(path):
                continue
            scanned += 1
            reason, inner_hits = classify_link(path, dirname)
            if reason:
                hits.append("%s|%s" % (path.replace(os.sep, "/"), reason))
            hits.extend(inner_hits)
            dirnames.remove(dirname)
        for filename in sorted(filenames):
            path = os.path.join(dirpath, filename)
            scanned += 1
            if os.path.islink(path):
                reason, inner_hits = classify_link(path, filename)
            else:
                reason, inner_hits = classify(path, filename)
            if reason:
                hits.append("%s|%s" % (path.replace(os.sep, "/"), reason))
            hits.extend(inner_hits)
    return scanned, len(hits), hits


def main(argv: list[str]) -> int:
    targets = argv[1:]
    if not targets:
        print("GATE|FAIL|no-scan-target")
        return 1
    total_scanned = 0
    all_hits: list[str] = []
    for target in targets:
        scanned, hits, names = scan_dir(target)
        if scanned < 0:
            print("SCAN|%s|absent|scanned=0" % target.replace(os.sep, "/"))
            continue
        total_scanned += scanned
        all_hits.extend(names)
        print("SCAN|%s|scanned=%d|hits=%d" % (target.replace(os.sep, "/"), scanned, hits))
        for name in names:
            print("HIT|%s" % name)
    verdict = "PASS" if not all_hits else "FAIL"
    # R9.1 的 L2-B finding B-02（P1）：所有扫描点都 `absent` 时旧实现照样 `PASS / exit 0`，
    # 于是"根本没扫"与"扫过且干净"在退出码上不可分辨 —— 而接线方（build.gradle.kts）完全可能
    # 在一次只跑 `bundleRelease` 或 clean 后未打包的调用里把三个目标全打空。
    # 收紧为：**一个文件都没扫到就报红**（"只许加强、不许放松"，且没有任何绕过开关）。
    if total_scanned == 0:
        print("GATE|FAIL|reason=nothing-scanned|targets=%d" % len(targets))
        print("隐私硬闸门没有扫到任何文件：扫描点全部缺席或全部为空目录。"
              "这不是'干净'——请检查接线参数是否指向本次构建真实存在的产物路径。", file=sys.stderr)
        return 1
    print("GATE|%s|scanned=%d|hits=%d|targets=%d" % (verdict, total_scanned, len(all_hits), len(targets)))
    if all_hits:
        print("隐私硬闸门命中：APK 内不得含任何用户数据（AGENTS.md §5 第 1 条）。"
              "整改方式=删除该文件/改由用户数据目录承载，**不存在绕过开关**。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
