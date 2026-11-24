#!/usr/bin/env python3
"""Raw Dump exports, cheaply.

Reads either export the app writes:
  - compact text  (EXPORT ALL, darkindex-dumps-compact v1, `.txt`)
  - full JSON     (long-press EXPORT ALL, darkindex-raw-dumps, `.json`; also a single dump's JSON)

Usage:
  python3 -I tools/dumps.py list    FILE             one line per dump: id, time, screen, result
  python3 -I tools/dumps.py compact FILE.json        print a full JSON export in the compact format
  python3 -I tools/dumps.py text    FILE ID          the dump's exact screen text (shared lines and nav restored)
  python3 -I tools/dumps.py fixture FILE ID PATH     write that text to PATH (a test fixture)
  python3 -I tools/dumps.py stats   FILE             size of each dump section, to see where the bytes go

The compact format is produced by RawDumpCompact.kt in the app; `compact` here mirrors it for old JSON exports.
"""
import json
import re
import sys

NAV = ["HOME", "SCAN", "PROCESSES", "APPS", "STORE"]
CREWS = re.compile(r"CREWS(\s+\d+)?$")
NAV_TEXT = "\n".join(NAV + ["CREWS"])
SHARED_MIN_LENGTH = 40


def strip_nav(text):
    lines = text.split("\n")
    end = len(lines)
    while end > 0 and not lines[end - 1].strip():
        end -= 1
    if end < len(NAV) + 1 or not CREWS.fullmatch(lines[end - 1].strip()):
        return text, False
    start = end - 1 - len(NAV)
    if any(lines[start + i].strip() != NAV[i] for i in range(len(NAV))):
        return text, False
    return "\n".join(lines[:start]), True


# ---------------------------------------------------------------- reading

def load(path):
    """Returns a list of dumps: {id, utc, screen, result, nav, text, raw}."""
    with open(path, encoding="utf-8") as f:
        data = f.read()
    if data.lstrip().startswith("{"):
        return load_json(json.loads(data))
    return load_compact(data)


def load_json(root):
    dumps = root.get("dumps", [root])
    out = []
    for d in dumps:
        text, nav = strip_nav(d.get("rawText", ""))
        out.append({"id": d.get("id"), "utc": d.get("capturedAtUtc", ""), "screen": d.get("detectedType"),
                    "result": d.get("result"), "nav": nav, "text": text, "raw": d})
    return out


def load_compact(data):
    shared, dumps, cur, in_text = {}, [], None, False
    for line in data.split("\n"):
        if line.startswith("## dump "):
            parts = line[3:].split(" · ")
            cur = {"id": int(parts[0].split()[1]), "utc": parts[1], "screen": parts[2], "nav": "nav" in parts[3:],
                   "result": None, "text_lines": [], "same": None}
            dumps.append(cur)
            in_text = False
        elif cur is None:
            m = re.match(r"§(\d+) (.*)$", line)
            if m:
                shared["§" + m.group(1)] = m.group(2)
        elif in_text:
            cur["text_lines"].append(shared.get(line, line))
        elif line.startswith("result: "):
            cur["result"] = line[8:]
        elif line == "text:":
            in_text = True
        elif line.startswith("text: same as #"):
            cur["same"] = int(line[len("text: same as #"):])
    by_id = {}
    for d in dumps:
        if d["same"] is not None:
            d["text"] = by_id[d["same"]]["text"]
        else:
            lines = d["text_lines"]
            if lines and lines[-1] == "":  # the file's trailing newline
                lines = lines[:-1]
            d["text"] = "\n".join(lines)
        by_id[d["id"]] = d
    return dumps


def exact_text(d):
    return d["text"] + ("\n" + NAV_TEXT if d["nav"] else "")


# ---------------------------------------------------------------- compact (mirror of RawDumpCompact.kt)

def compact(path, out):
    with open(path, encoding="utf-8") as f:
        root = json.load(f)
    dumps = load_json(root)
    first, same = {}, []
    for d in dumps:
        same.append(first.get(d["text"]))
        first.setdefault(d["text"], d["id"])
    counts = {}
    for d, s in zip(dumps, same):
        if s is None:
            for l in set(x for x in d["text"].split("\n") if len(x) >= SHARED_MIN_LENGTH):
                counts[l] = counts.get(l, 0) + 1
    shared = [l for l, c in counts.items() if c >= 2]
    ref = {l: "§%d" % i for i, l in enumerate(shared)}
    apps = sorted({"%s (%s)" % (d["raw"].get("app", {}).get("versionName"), d["raw"].get("app", {}).get("versionCode"))
                   for d in dumps if d["raw"].get("app")})
    w = out.write
    w("# darkindex-dumps-compact v1\n")
    w("# exported %s · %d dumps · app %s\n" % (short(root.get("exportedAtUtc", "?")), len(dumps), ", ".join(apps)))
    w("# Bottom nav removed from texts marked nav. §n = line n of ## shared.\n")
    if shared:
        w("## shared\n")
        for i, l in enumerate(shared):
            w("§%d %s\n" % (i, l))
    for d, s in zip(dumps, same):
        r = d["raw"]
        head = ["## dump %s" % d["id"], short(d["utc"]), d["screen"] or "?"] + (["nav"] if d["nav"] else [])
        w(" · ".join(head) + "\n")
        if r.get("fallbackUsed"):
            w("fallback: yes\n")
        if r.get("result"):
            w("result: %s\n" % r["result"])
        if r.get("parsersMatched"):
            w("parsers: %s\n" % ", ".join(r["parsersMatched"]))
        if r.get("error"):
            w("error: %s\n" % r["error"].replace("\n", "\n  "))
        st = r.get("logStats")
        if st:
            w("log: %s timestamped, %s interpreted, %s discarded, %s partial\n" % tuple(
                st.get(k, "?") for k in ("timestampedLines", "interpreted", "discarded", "partial")))
        for x in r.get("discardedLogLines") or []:
            w("discarded: %s:%s | %s\n" % (x.get("stage"), x.get("reason"), x.get("line")))
        db = r.get("dbEffects") or {}
        parts = []
        if db.get("recordsBefore") != db.get("recordsAfter"):
            parts.append("records %s→%s" % (db.get("recordsBefore"), db.get("recordsAfter")))
        for k in ("created", "updated", "removed"):
            if db.get(k):
                parts.append("%s %d: %s" % (k, len(db[k]), ", ".join(map(str, db[k]))))
        if db.get("profileChanges"):
            parts.append("profile " + ", ".join("%s %s→%s" % (k, v[0], v[1]) for k, v in db["profileChanges"].items()))
        for k in ("siphonAlertsAdded", "siphonAlertsCleared"):
            if db.get(k):
                parts.append("%s: %s" % (k, ", ".join(map(str, db[k]))))
        if db.get("knownSiphonsBefore") != db.get("knownSiphonsAfter"):
            parts.append("knownSiphons %s→%s" % (db.get("knownSiphonsBefore"), db.get("knownSiphonsAfter")))
        if parts:
            w("db: %s\n" % " · ".join(parts))
        for c in db.get("walletConflicts") or []:
            w("walletConflict: %s\n" % json.dumps(c, ensure_ascii=False, separators=(",", ":")))
        for k, v in (r.get("interpretation") or {}).items():
            if k == "Log" and isinstance(v, list):
                write_log(v, d["text"], w)
            else:
                emit("interp " + k, v, "", w)
        if s is not None:
            w("text: same as #%s\n" % s)
        else:
            w("text:\n")
            for l in d["text"].split("\n"):
                w(ref.get(l, l) + "\n")


def write_log(entries, text, w):
    w("interp Log (%d):\n" % len(entries))
    lines = [l.strip() for l in text.split("\n")]
    used = set()
    for u in entries:
        line = u.get("line")
        idx = next((i for i, l in enumerate(lines) if i not in used and l == line), -1) if line else -1
        if idx >= 0:
            used.add(idx)
        cols = ["L%d" % idx if idx >= 0 else "L?", u.get("ip") or "-", u.get("wallet") or "-",
                str(u["amount"]) if u.get("amount") is not None else "-"]
        if u.get("ownTheft"):
            cols.append("own")
        if u.get("software"):
            cols.append("sw:" + u["software"])
        if u.get("ownedSoftware"):
            cols.append("owned")
        w("  " + " ".join(cols) + (" | " + line if idx < 0 and line else "") + "\n")


def emit(label, v, indent, w):
    one = json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    if len(one) <= 160 or not isinstance(v, (dict, list)):
        w("%s%s: %s\n" % (indent, label, one))
        return
    w("%s%s:\n" % (indent, label))
    items = v.items() if isinstance(v, dict) else (("[%d]" % i, c) for i, c in enumerate(v))
    for k, c in items:
        emit(k, c, indent + "  ", w)


def short(s):
    return re.sub(r"\.\d+Z$", "Z", s.replace("T", " "))


# ---------------------------------------------------------------- commands

def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    cmd, path = argv[1], argv[2]
    if cmd == "compact":
        compact(path, sys.stdout)
    elif cmd == "list":
        for d in load(path):
            print("#%s  %s  %-14s %s" % (d["id"], short(d["utc"]), d["screen"], d["result"] or ""))
    elif cmd in ("text", "fixture"):
        d = next(x for x in load(path) if str(x["id"]) == argv[3])
        if cmd == "text":
            sys.stdout.write(exact_text(d) + "\n")
        else:
            with open(argv[4], "w", encoding="utf-8") as f:
                f.write(exact_text(d))
            print("wrote", argv[4])
    elif cmd == "stats":
        for d in load(path):
            raw = d.get("raw") or {}
            sizes = {k: len(json.dumps(v)) for k, v in raw.items()} if raw else {"text": len(d["text"])}
            print("#%s %s" % (d["id"], ", ".join("%s %d" % kv for kv in sorted(sizes.items(), key=lambda kv: -kv[1])[:5])))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
