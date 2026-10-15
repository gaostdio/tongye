"""Capture, inspect and restore only the isolated tongye_test AVD media tests."""
import hashlib
import json
import sqlite3
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "deliverables/verification/media"
ADB = Path.home() / ".codex/android-runtime/focusmirror/sdk/platform-tools/adb.exe"
PACKAGES = ("app.tongye.diary", "app.tongye.diary.peer")

def adb(*args, check=True):
    return subprocess.run([str(ADB), "-s", "emulator-5554", *args], capture_output=True, check=check)

assert adb("emu", "avd", "name").stdout.splitlines()[0].strip() == b"tongye_test"
WORK.mkdir(parents=True, exist_ok=True)

def capture(package, directory):
    directory.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", package)
    for name in ("tongye.db", "tongye.db-wal", "tongye.db-shm"):
        result = adb("exec-out", "run-as", package, "cat", "databases/" + name, check=False)
        if result.returncode == 0:
            (directory / name).write_bytes(result.stdout)
    with sqlite3.connect(directory / "tongye.db") as source, sqlite3.connect(directory / "snapshot.db") as target:
        source.backup(target)

def documents(database):
    with sqlite3.connect(database) as connection:
        rows = connection.execute("SELECT documentId,chunkIndex,json FROM workspace_documents ORDER BY documentId,chunkIndex").fetchall()
    chunks = {}
    for key, index, value in rows:
        chunks.setdefault(key, []).append(value)
    return {key: json.loads("".join(parts)) for key, parts in chunks.items()}

def refs(page):
    return page.get("photos", []) + page.get("videos", []) + [e["content"] for e in page.get("elements", []) if e["type"] in ("IMAGE", "VIDEO")]

mode = sys.argv[1]
if mode == "capture":
    for package in PACKAGES:
        directory = WORK / ("before-" + package)
        assert not (directory / "snapshot.db").exists(), "Never overwrite the original test snapshot"
        capture(package, directory)
    print("Captured both original test databases")
elif mode == "check":
    expected = {hashlib.sha256((WORK / name).read_bytes()).hexdigest() for name in ("two-colors.gif", "test-clip.mp4")}
    for package in PACKAGES:
        directory = WORK / ("after-" + package)
        capture(package, directory)
        pages = documents(directory / "snapshot.db")
        originals = documents(WORK / ("before-" + package) / "snapshot.db")
        found = set()
        for key, page in pages.items():
            if not key.startswith("diary:"):
                continue
            if key in originals:
                original = dict(originals[key]);original["videos"] = original.get("videos") or []
                normalized = dict(page);normalized["videos"] = normalized.get("videos") or []
                assert normalized == original, "Existing page changed: " + key
            else:
                for path in refs(page):
                    assert path.startswith("/data/user/0/" + package + "/files/photos/")
                    digest = adb("shell", "run-as", package, "sha256sum", path).stdout.decode().split()[0]
                    assert digest in expected
                    found.add(digest)
        assert found == expected, "Both original GIF and MP4 must be present"
    print(json.dumps({"apps": 2, "gif_and_mp4": "original SHA-256 matched", "existing_pages": "unchanged"}))
elif mode == "backup":
    expected = {hashlib.sha256((WORK / name).read_bytes()).hexdigest() for name in ("two-colors.gif", "test-clip.mp4")}
    with zipfile.ZipFile(WORK / "media-backup.zip") as archive:
        assert archive.testzip() is None
        state = json.loads(archive.read("diary.json"))
        assert state.get("exchange") is None and not any(d["visibility"] == "PRIVATE" for d in state["diaries"])
        found = {hashlib.sha256(archive.read(path)).hexdigest() for d in state["diaries"] for path in refs(d) if not path.startswith("sample:")}
        assert expected <= found
    print(json.dumps({"backup": "CRC passed", "gif_and_mp4": "original SHA-256 matched", "private": "excluded"}))
elif mode == "restore":
    for package in PACKAGES:
        adb("shell", "am", "force-stop", package)
        adb("push", str(WORK / ("before-" + package) / "snapshot.db"), "/data/local/tmp/tongye-media-original.db")
        adb("shell", "run-as", package, "rm", "-f", "databases/tongye.db-wal", "databases/tongye.db-shm")
        adb("shell", "run-as", package, "cp", "/data/local/tmp/tongye-media-original.db", "databases/tongye.db")
    print("Restored both original test databases")
else:
    raise SystemExit("Use capture | check | backup | restore")
