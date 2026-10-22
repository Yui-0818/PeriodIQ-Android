"""把 `frontend/` 前端源复制进 `app/src/main/assets/www/`（构建期产物，不入库）。

R14.1 起前端唯一来源是仓库内 `frontend/`：
① 源必须存在且含 `index.html`；② 源内不得出现数据文件；③ 清空目标；④ 逐文件复制；
⑤ 目标自检（`index.html`、`manifest.json` 必须存在）。任一步不符即退出码 1，**无绕过开关**。
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SRC = REPO_ROOT / "frontend"
DST = REPO_ROOT / "app" / "src" / "main" / "assets" / "www"
LABEL = "assets/www"
DATA_SUFFIXES = (".db", ".sqlite", ".sqlite3", ".bak")
SIDECAR_SUFFIXES = tuple(f"{suffix}{tail}" for suffix in (".db", ".sqlite", ".sqlite3") for tail in ("-wal", "-shm", "-journal"))


def fail(message: str) -> int:
    print(f"[{LABEL}] FAIL {message}")
    return 1


def is_data_file(path: Path) -> bool:
    name = path.name.lower()
    return name.endswith(DATA_SUFFIXES) or name.endswith(SIDECAR_SUFFIXES)


def main() -> int:
    if not SRC.is_dir():
        return fail(f"源目录不存在：{SRC.relative_to(REPO_ROOT)}")
    if not (SRC / "index.html").is_file():
        return fail("源目录缺少 index.html")

    files = sorted(path for path in SRC.rglob("*") if path.is_file())
    data_files = [path for path in files if is_data_file(path)]
    if data_files:
        for path in data_files:
            print(f"FAIL 源内禁止的数据文件：{path.relative_to(REPO_ROOT)}")
        return fail(f"发现 {len(data_files)} 个数据文件，未复制")

    if DST.is_symlink() or (DST.exists() and not DST.is_dir()):
        DST.unlink()
    elif DST.is_dir():
        shutil.rmtree(DST)
    DST.mkdir(parents=True)

    copied = 0
    for source in files:
        relative = source.relative_to(SRC)
        target = DST / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        copied += 1

    for required in ("index.html", "manifest.json"):
        if not (DST / required).is_file():
            return fail(f"目标缺少 {required}")

    if copied != len(files):
        return fail(f"复制数量不符：源 {len(files)}，目标 {copied}")

    print(f"[{LABEL}] source=frontend files={copied}")
    print(f"[{LABEL}] target={DST.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
