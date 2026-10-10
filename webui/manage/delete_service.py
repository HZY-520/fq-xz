#!/usr/bin/env python3
"""
番茄小说下载器 — 删除服务（sidecar）

背景
----
上游后端（tomato-novel-downloader v2.4.15）**没有提供任何删除接口**，
只读挂载文件系统无法实现"删除已完成小说 + 对应文件"，因此这里提供一个
极小的本地 HTTP 服务，与后端容器**共享同一个 /app 数据卷**，专门负责删除。

安全设计
--------
1. 只监听 127.0.0.1（不暴露公网），由 Nginx 反代 /api/manage/ 转发过来。
2. 所有路径都在 /app 根目录下做 canonicalize + 前缀校验，杜绝目录穿越。
3. 可选共享密钥：设置 MANAGE_TOKEN 后，请求必须带 X-Manage-Token 头。
4. 拒绝对"正在下载"的书执行删除（读取后端 /api/jobs 判断）。
5. 支持 ?dry_run=1 预演，只返回将删除的清单，不实际删除。

接口
----
GET  /api/manage/delete?path=<rel_path>|book_id=<id>[&dry_run=1]   预演（只读）
POST /api/manage/delete  { "path": "...", "book_id": "..." }       执行删除
GET  /api/manage/history[?book_id=]                                列出历史记录
POST /api/manage/history/delete { "book_id": "...", "timestamp": "..." }  删历史记录
GET  /api/manage/health                                            健康检查
"""

import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

# ── 配置 ────────────────────────────────────────────────────────────────
LIBRARY_ROOT = Path(os.environ.get("LIBRARY_ROOT", "/app")).resolve()
# 后端日志/历史目录（history 文件在其中），用于删除下载历史记录
DATA_ROOT = Path(os.environ.get("MANAGE_DATA_DIR", "/data")).resolve()
HISTORY_FILE = DATA_ROOT / "logs" / "download_history.jsonl"
BIND_HOST = os.environ.get("MANAGE_BIND", "127.0.0.1")
BIND_PORT = int(os.environ.get("MANAGE_PORT", "18425"))
MANAGE_TOKEN = os.environ.get("MANAGE_TOKEN", "").strip()
BACKEND_BASE = os.environ.get("BACKEND_BASE", "http://127.0.0.1:18423").rstrip("/")

# 只允许删除这些类型的输出文件（与后端下载白名单一致）
ALLOWED_EXT = {"epub", "txt", "mp3", "wav"}

# 封面文件扩展名（后端预览/下载时会在书号目录里缓存封面）
COVER_EXTS = ("jpg", "jpeg", "png", "webp")

# 缓存目录名：以纯数字书籍 ID 开头（可带 _书名 后缀）
CACHE_DIR_RE = re.compile(r"^(\d+)(?:_.*)?$")

# 有声小说目录后缀
AUDIO_SUFFIX = "_audio"


def log(msg: str) -> None:
    print(msg, flush=True)


# ── 下载历史（JSONL，后端内部数据）──────────────────────────────────────
def read_history() -> tuple[list[dict], list[str]]:
    """读取历史记录。返回 (合法记录列表, 原始行列表)。

    后端实现：每行一条 JSON，读取时逐行解析、坏行跳过，最后 reverse()。
    这里保留原始行文本，删除时按行重写，避免破坏无法解析的行。
    """
    if not HISTORY_FILE.is_file():
        return [], []
    try:
        raw = HISTORY_FILE.read_text(encoding="utf-8", errors="replace")
    except Exception as e:
        log(f"[warn] 读取历史文件失败: {e}")
        return [], []

    lines = [l for l in raw.splitlines() if l.strip()]
    records = []
    for line in lines:
        try:
            d = json.loads(line)
            if isinstance(d, dict):
                records.append(d)
        except Exception:
            continue
    return records, lines


def record_matches(d: dict, book_id: str | None, timestamp: str | None) -> bool:
    if book_id and str(d.get("book_id") or "") != str(book_id):
        return False
    if timestamp and str(d.get("timestamp") or "") != str(timestamp):
        return False
    return True


def history_plan(book_id: str | None, timestamp: str | None) -> dict:
    """计算将删除的历史记录（不落盘）。"""
    if not book_id and not timestamp:
        raise ValueError("必须提供 book_id 或 timestamp")

    records, lines = read_history()
    if not lines:
        raise FileNotFoundError("历史记录文件不存在或为空")

    matched, kept = [], []
    for line in lines:
        try:
            d = json.loads(line)
        except Exception:
            kept.append(line)
            continue
        if isinstance(d, dict) and record_matches(d, book_id, timestamp):
            matched.append(d)
        else:
            kept.append(line)

    return {"matched": matched, "kept": kept, "total": len(lines)}


def write_history(kept_lines: list[str]) -> None:
    """原子重写历史文件（先写临时文件再 rename，避免写坏）。"""
    HISTORY_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = HISTORY_FILE.with_suffix(".jsonl.tmp")
    tmp.write_text(
        "".join(l + "\n" for l in kept_lines), encoding="utf-8"
    )
    os.replace(tmp, HISTORY_FILE)


# ── 路径安全 ────────────────────────────────────────────────────────────
def safe_resolve(rel_path: str) -> Path:
    """把相对路径解析到 LIBRARY_ROOT 下，并确保没有越界。

    返回可以安全操作的绝对路径；越界或非法则抛 ValueError。
    """
    rel = unquote(str(rel_path or "")).replace("\\", "/").strip()
    rel = rel.lstrip("/")
    if not rel:
        raise ValueError("path 不能为空")

    # 逐段校验，直接拒绝 .. 段（比事后 canonicalize 更直观）
    parts = [p for p in rel.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        raise ValueError("path 非法（含 ..）")
    if not parts:
        raise ValueError("path 不能为空")

    candidate = LIBRARY_ROOT.joinpath(*parts)

    # 对已存在的路径做 canonicalize 校验，防符号链接绕过
    if candidate.exists():
        resolved = candidate.resolve()
        if resolved != LIBRARY_ROOT and LIBRARY_ROOT not in resolved.parents:
            raise ValueError("path 越界")
        return resolved

    # 不存在的路径：校验其父目录
    parent = candidate.parent
    if parent.exists():
        rp = parent.resolve()
        if rp != LIBRARY_ROOT and LIBRARY_ROOT not in rp.parents:
            raise ValueError("path 越界")
    return candidate


def is_cache_dir(p: Path) -> bool:
    """判断是否为后端书籍目录（以纯数字书籍 ID 开头）。

    包括两种情况：
      1. 完整缓存目录：含 status.json / downloaded_chapters.jsonl / chapter_status_*.json / images/
      2. 封面残留目录：后端读取封面后会留下 cover.jpg（书被删掉后只剩它），
         也一并视为可清理内容，避免留下空壳目录。
    """
    if not p.is_dir():
        return False
    m = CACHE_DIR_RE.match(p.name)
    if not m:
        return False
    # 具备任一缓存标记文件才认定
    if (
        (p / "status.json").is_file()
        or (p / "downloaded_chapters.jsonl").is_file()
        or any(p.glob("chapter_status_*.json"))
        or (p / "images").is_dir()
    ):
        return True
    # 仅剩封面（或完全为空）的书号目录：视为残留，可清理
    entries = [e.name for e in p.iterdir()]
    if not entries:
        return True
    return all(
        e == "images" or e.lower() in {f"cover.{x}" for x in COVER_EXTS}
        for e in entries
    )


def cache_book_name(cache_dir: Path) -> str | None:
    """从缓存目录的 status.json 里读出书名，用于反查输出文件。

    实测 status.json 结构：{ book_id, book_name, author, chapter_count, ... }
    """
    status = cache_dir / "status.json"
    if not status.is_file():
        return None
    try:
        with status.open("r", encoding="utf-8", errors="replace") as f:
            data = json.load(f)
    except Exception as e:
        log(f"[warn] 读取 {status} 失败: {e}")
        return None
    if isinstance(data, dict):
        name = str(data.get("book_name") or "").strip()
        return name or None
    return None


def outputs_by_name(name: str) -> list[Path]:
    """按书名找出所有输出文件（<书名>.epub/.txt/.mp3/.wav）。"""
    out = []
    if not name or not LIBRARY_ROOT.is_dir():
        return out
    for ext in ALLOWED_EXT:
        p = LIBRARY_ROOT / f"{name}.{ext}"
        if p.is_file():
            out.append(p)
    return out


def collect_cache_dirs(book_id: str) -> list[Path]:
    """按书籍 ID 找出所有缓存目录（支持 7085... 与 7085..._旧书名 两种命名）。"""
    out = []
    if not LIBRARY_ROOT.is_dir():
        return out
    for child in LIBRARY_ROOT.iterdir():
        if not child.is_dir():
            continue
        m = CACHE_DIR_RE.match(child.name)
        if m and m.group(1) == str(book_id) and is_cache_dir(child):
            out.append(child)
    return out


def output_files_for_books(book_ids: set[str]) -> list[Path]:
    """找出属于这些书籍 ID 的输出文件。

    输出文件名与缓存目录名共用"纯数字 ID"前缀或纯书名，两种约定都覆盖：
      - <book_id>.epub / <book_id>_<书名>.epub
      - 由前端按书名分组后，用 path 模式直接删除更精确
    """
    out = []
    if not LIBRARY_ROOT.is_dir():
        return out
    for child in LIBRARY_ROOT.iterdir():
        if not child.is_file():
            continue
        if child.suffix.lstrip(".").lower() not in ALLOWED_EXT:
            continue
        stem = child.stem
        m = CACHE_DIR_RE.match(stem)
        if m and m.group(1) in book_ids:
            out.append(child)
    return out


def audio_dirs_for_names(names: set[str]) -> list[Path]:
    out = []
    if not LIBRARY_ROOT.is_dir():
        return out
    for name in names:
        d = LIBRARY_ROOT / f"{name}{AUDIO_SUFFIX}"
        if d.is_dir() and d.resolve() != LIBRARY_ROOT:
            out.append(d)
    return out


# ── 下载状态检查 ────────────────────────────────────────────────────────
def active_job_book_ids() -> set[str]:
    """读取后端任务列表，返回正在 排队/下载 中的书籍 ID。"""
    try:
        with urllib.request.urlopen(f"{BACKEND_BASE}/api/jobs", timeout=5) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:  # 后端不可用时保守处理：视为无法确认
        log(f"[warn] 读取后端任务失败，跳过活跃检查: {e}")
        return set()

    active = set()
    for job in data.get("items") or []:
        if job.get("state") in ("queued", "running"):
            bid = str(job.get("book_id") or "").strip()
            if bid:
                active.add(bid)
    return active


# ── 计划构建 ────────────────────────────────────────────────────────────
def build_plan(payload: dict) -> dict:
    """根据请求构建删除计划（不执行任何删除）。

    返回 { targets: [ {path, kind, size} ], book_ids: set, names: set }
    """
    targets: list[Path] = []
    names: set[str] = set()
    book_ids: set[str] = set()

    rel_path = (payload.get("path") or "").strip()
    book_id = str(payload.get("book_id") or "").strip()

    if book_id:
        if not book_id.isdigit():
            raise ValueError("book_id 必须为纯数字")
        book_ids.add(book_id)
        # 该书的缓存目录（同时从 status.json 反查出书名）
        for d in collect_cache_dirs(book_id):
            targets.append(d)
            nm = cache_book_name(d)
            if nm:
                names.add(nm)
        # 以 ID 命名的输出文件
        for f in output_files_for_books({book_id}):
            targets.append(f)
            names.add(f.stem)
        # 关键：输出文件通常以「书名」命名而非书号，需用 status.json 里的书名反查
        for nm in list(names):
            for f in outputs_by_name(nm):
                targets.append(f)

    if rel_path:
        target = safe_resolve(rel_path)
        if not target.exists():
            raise FileNotFoundError(f"目标不存在: {rel_path}")

        if target.is_file():
            if target.suffix.lstrip(".").lower() not in ALLOWED_EXT:
                raise ValueError(f"不允许删除该类型文件: {target.suffix}")
            targets.append(target)
            names.add(target.stem)

            # 同名缓存目录（文件名 == 目录名 的变体）
            sibling = LIBRARY_ROOT / target.stem
            if is_cache_dir(sibling):
                targets.append(sibling)

            # 同名有声小说目录
            audio = LIBRARY_ROOT / f"{target.stem}{AUDIO_SUFFIX}"
            if audio.is_dir():
                targets.append(audio)

        elif target.is_dir():
            targets.append(target)
            names.add(target.name)
            # 目录名形如 <书ID> / <书ID>_<书名>：反查输出文件
            m = CACHE_DIR_RE.match(target.name)
            if m:
                bid = m.group(1)
                book_ids.add(bid)
                # 先取缓存目录里的书名
                nm = cache_book_name(target)
                if nm:
                    names.add(nm)
                # 目录名里的 _书名 后缀也作为候选
                if "_" in target.name:
                    names.add(target.name.split("_", 1)[1])
                for f in output_files_for_books({bid}):
                    targets.append(f)
                    names.add(f.stem)
            # 用收集到的书名反查输出文件
            for nm in list(names):
                for f in outputs_by_name(nm):
                    targets.append(f)

    # 按书名找同名音频目录
    for d in audio_dirs_for_names(names):
        if d not in targets:
            targets.append(d)

    # 去重 + 过滤不存在的
    seen, uniq = set(), []
    for t in targets:
        key = str(t)
        if key in seen or not t.exists():
            continue
        seen.add(key)
        uniq.append(t)

    return {"targets": uniq, "book_ids": book_ids, "names": names}


def size_of(p: Path) -> int:
    try:
        if p.is_file():
            return p.stat().st_size
        total = 0
        for root, _dirs, files in os.walk(p):
            for f in files:
                try:
                    total += os.path.getsize(os.path.join(root, f))
                except OSError:
                    pass
        return total
    except OSError:
        return 0


def describe(targets: list[Path]) -> list[dict]:
    out = []
    for t in targets:
        out.append(
            {
                "name": t.name,
                "rel_path": str(t.relative_to(LIBRARY_ROOT)),
                "kind": "dir" if t.is_dir() else "file",
                "size": size_of(t),
            }
        )
    return out


def execute(targets: list[Path]) -> list[dict]:
    removed = []
    for t in targets:
        try:
            if t.is_dir():
                shutil.rmtree(t)
            else:
                t.unlink()
            removed.append({"rel_path": str(t.relative_to(LIBRARY_ROOT)), "ok": True})
        except Exception as e:
            removed.append(
                {"rel_path": str(t.relative_to(LIBRARY_ROOT)), "ok": False, "error": str(e)}
            )
    return removed


# ── HTTP 处理 ───────────────────────────────────────────────────────────
class Handler(BaseHTTPRequestHandler):
    server_version = "TND-Manage/1.0"

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self) -> bool:
        if not MANAGE_TOKEN:
            return True
        return self.headers.get("X-Manage-Token", "") == MANAGE_TOKEN

    def _payload(self, query: dict) -> dict:
        p = {k: (v[0] if isinstance(v, list) and v else v) for k, v in query.items()}
        return p

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"

        if path == "/api/manage/health":
            self._json(
                200,
                {
                    "ok": True,
                    "library_root": str(LIBRARY_ROOT),
                    "history_file": str(HISTORY_FILE),
                    "history_exists": HISTORY_FILE.is_file(),
                    "token": bool(MANAGE_TOKEN),
                },
            )
            return

        # 列出历史记录（只读，便于前端/脚本核对）
        if path == "/api/manage/history":
            if not self._auth_ok():
                self._json(401, {"error": "unauthorized"})
                return
            query = parse_qs(parsed.query)
            bid = (query.get("book_id") or [""])[0] or None
            records, _ = read_history()
            if bid:
                records = [r for r in records if str(r.get("book_id") or "") == bid]
            records.reverse()  # 与后端 /api/history 顺序一致（新的在前）
            self._json(
                200,
                {"items": records, "total": len(records), "file": str(HISTORY_FILE)},
            )
            return

        if path == "/api/manage/delete":
            if not self._auth_ok():
                self._json(401, {"error": "unauthorized"})
                return
            query = parse_qs(parsed.query)
            payload = {k: (v[0] if v else "") for k, v in query.items()}
            dry = str(payload.get("dry_run", "")).lower() in ("1", "true", "yes")
            try:
                plan = build_plan(payload)
            except FileNotFoundError as e:
                self._json(404, {"error": str(e)})
                return
            except ValueError as e:
                self._json(400, {"error": str(e)})
                return
            self._json(
                200,
                {
                    "dry_run": True,
                    "would_delete": describe(plan["targets"]),
                    "total_size": sum(size_of(t) for t in plan["targets"]),
                },
            )
            return

        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"

        if path not in ("/api/manage/delete", "/api/manage/history/delete"):
            self._json(404, {"error": "not found"})
            return
        if not self._auth_ok():
            self._json(401, {"error": "unauthorized"})
            return

        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            payload = json.loads(raw.decode("utf-8", "replace") or "{}")
        except Exception as e:
            self._json(400, {"error": f"请求体解析失败: {e}"})
            return

        # ── 删除下载历史记录 ──
        if path == "/api/manage/history/delete":
            self._handle_history_delete(payload)
            return

        try:
            plan = build_plan(payload)
        except FileNotFoundError as e:
            self._json(404, {"error": str(e)})
            return
        except ValueError as e:
            self._json(400, {"error": str(e)})
            return

        if not plan["targets"]:
            self._json(404, {"error": "没有匹配到可删除的文件"})
            return

        # 正在下载的书禁止删除
        active = active_job_book_ids()
        if active:
            hit = active & plan["book_ids"]
            if not hit:
                # 也检查按书名匹配（缓存目录名含书名的情况）
                for t in plan["targets"]:
                    m = CACHE_DIR_RE.match(t.name)
                    if m and m.group(1) in active:
                        hit = {m.group(1)}
                        break
            if hit:
                self._json(
                    409,
                    {
                        "error": "该书正在下载中，请先取消任务再删除",
                        "book_ids": sorted(hit),
                    },
                )
                return

        before = describe(plan["targets"])
        result = execute(plan["targets"])
        failed = [r for r in result if not r.get("ok")]
        self._json(
            200 if not failed else 207,
            {
                "ok": not failed,
                "deleted": [r["rel_path"] for r in result if r.get("ok")],
                "failed": failed,
                "total_size": sum(t["size"] for t in before),
            },
        )

    def _handle_history_delete(self, payload: dict) -> None:
        """删除下载历史记录（只动 JSONL 文件，无需重启后端即可生效）。"""
        book_id = str(payload.get("book_id") or "").strip() or None
        timestamp = str(payload.get("timestamp") or "").strip() or None
        try:
            plan = history_plan(book_id, timestamp)
        except FileNotFoundError as e:
            self._json(404, {"error": str(e)})
            return
        except ValueError as e:
            self._json(400, {"error": str(e)})
            return

        if not plan["matched"]:
            self._json(
                404,
                {
                    "error": "没有匹配的历史记录（可能已被删除）",
                    "book_id": book_id,
                    "timestamp": timestamp,
                },
            )
            return

        try:
            write_history(plan["kept"])
        except Exception as e:
            self._json(500, {"error": f"写入历史文件失败: {e}"})
            return

        log(
            f"[history] 删除 {len(plan['matched'])} 条记录 "
            f"(book_id={book_id} timestamp={timestamp})，剩余 {len(plan['kept'])} 条"
        )
        self._json(
            200,
            {
                "ok": True,
                "deleted_count": len(plan["matched"]),
                "deleted": [
                    {
                        "book_id": r.get("book_id"),
                        "book_name": r.get("book_name"),
                        "timestamp": r.get("timestamp"),
                    }
                    for r in plan["matched"]
                ],
                "remaining": len(plan["kept"]),
                "history_file": str(HISTORY_FILE),
            },
        )

    def log_message(self, fmt: str, *args) -> None:  # 收敛日志
        log(f"[manage] {self.address_string()} {fmt % args}")


def main() -> int:
    if not LIBRARY_ROOT.is_dir():
        log(f"[fatal] LIBRARY_ROOT 不存在: {LIBRARY_ROOT}")
        return 1
    log(f"[manage] library_root={LIBRARY_ROOT}")
    log(f"[manage] history_file={HISTORY_FILE} (exists={HISTORY_FILE.is_file()})")
    log(f"[manage] listening on http://{BIND_HOST}:{BIND_PORT}  token={'on' if MANAGE_TOKEN else 'off'}")
    ThreadingHTTPServer((BIND_HOST, BIND_PORT), Handler).serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
