"""Synthetic large-photo fixture, restricted to our tongye_test emulator; never physical devices."""
import base64
import hashlib
import json
import random
import sqlite3
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "deliverables/verification/large-photos"
ADB = Path.home() / ".codex/android-runtime/focusmirror/sdk/platform-tools/adb.exe"
PACKAGES = ("app.tongye.diary", "app.tongye.diary.peer")

def adb(*args, check=True):
    return subprocess.run([str(ADB), "-s", "emulator-5554", *args], capture_output=True, check=check)

assert adb("emu", "avd", "name").stdout.splitlines()[0].strip() == b"tongye_test", "Only the isolated test AVD is allowed"
WORK.mkdir(parents=True, exist_ok=True)

def capture(package, destination):
    destination.mkdir(parents=True, exist_ok=True)
    adb("shell", "am", "force-stop", package)
    for name in ("tongye.db", "tongye.db-wal", "tongye.db-shm"):
        result = adb("exec-out", "run-as", package, "cat", "databases/" + name, check=False)
        if result.returncode == 0:
            (destination / name).write_bytes(result.stdout)
    with sqlite3.connect(destination / "tongye.db") as source, sqlite3.connect(destination / "snapshot.db") as target:
        source.backup(target)

def documents(database):
    with sqlite3.connect(database) as connection:
        rows = connection.execute("SELECT documentId, chunkIndex, json FROM workspace_documents ORDER BY documentId, chunkIndex").fetchall()
    parts = {}
    for key, index, text in rows:
        parts.setdefault(key, []).append(text)
    return {key: json.loads("".join(value)) for key, value in parts.items()}

def replace_database(package, database):
    adb("shell", "am", "force-stop", package)
    adb("push", str(database), "/data/local/tmp/tongye-large-test.db")
    adb("shell", "run-as", package, "rm", "-f", "databases/tongye.db-wal", "databases/tongye.db-shm")
    adb("shell", "run-as", package, "cp", "/data/local/tmp/tongye-large-test.db", "databases/tongye.db")

mode = sys.argv[1]
if mode == "capture":
    for package in PACKAGES:
        destination = WORK / ("before-" + package)
        assert not (destination / "snapshot.db").exists(), "Do not overwrite the original snapshot; use a fresh verification directory"
        capture(package, destination)
    print("Captured both isolated databases")
elif mode == "fixture":
    original = WORK / ("before-" + PACKAGES[0]) / "snapshot.db"
    destination = WORK / "fixture.db"
    assert not destination.exists()
    destination.write_bytes(original.read_bytes())
    state = documents(original)["workspace"]
    photos = WORK / "photos"
    photos.mkdir(exist_ok=True)
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQ0AAAAASUVORK5CYII=")
    hashes = {}
    for index in range(120):
        file = photos / f"large-test-{index:03}.png"
        generator = random.Random(index)
        with file.open("wb") as output:
            output.write(png)
            remaining = 1024 * 1024 - len(png)
            while remaining:
                size = min(65536, remaining)
                output.write(generator.randbytes(size))
                remaining -= size
        hashes[file.name] = hashlib.sha256(file.read_bytes()).hexdigest()
    now = int(time.time() * 1000)
    with sqlite3.connect(destination) as connection:
        for index in range(12):
            page_id = f"large-test-page-{index}"
            page = {"id": page_id, "authorId": "me", "visibility": "PARTNER", "title": f"Export-stress-{index + 1:02}",
                    "body": "Synthetic originals: 10 x 1 MiB; testing export without reducing photos.", "date": "2026-10-10", "mood": "晴朗",
                    "photos": [f"/data/user/0/{PACKAGES[0]}/files/photos/large-test-{number:03}.png" for number in range(index * 10, index * 10 + 10)],
                    "elements": [], "strokes": [], "background": "奶油白", "isDiy": False, "favorite": False, "reactions": [], "comments": [],
                    "createdAt": now, "updatedAt": now, "updatedBy": "me", "deletedElements": {}, "deletedStrokes": {}}
            connection.execute("INSERT INTO workspace_documents(documentId,chunkIndex,json) VALUES(?,?,?)", ("diary:" + page_id, 0, json.dumps(page, ensure_ascii=False)))
            state["exchange"]["revision"] += 1
            state["exchange"]["revisions"][page_id] = state["exchange"]["revision"]
        connection.execute("DELETE FROM workspace_documents WHERE documentId='workspace'")
        connection.execute("INSERT INTO workspace_documents(documentId,chunkIndex,json) VALUES('workspace',0,?)", (json.dumps(state, ensure_ascii=False),))
    (WORK / "expected-hashes.json").write_text(json.dumps(hashes, indent=2))
    print(json.dumps({"photos": 120, "original_bytes": 120 * 1024 * 1024, "pages": 12}))
elif mode == "install-fixture":
    replace_database(PACKAGES[0], WORK / "fixture.db")
    adb("push", str(WORK / "photos"), "/data/local/tmp/tongye-large-photos")
    adb("shell", "run-as " + PACKAGES[0] + " sh -c 'cp /data/local/tmp/tongye-large-photos/*.png files/photos/'")
    print("Installed synthetic records, preserving identity and original pages")
elif mode == "check-received":
    capture(PACKAGES[1], WORK / "after-receive")
    pages = documents(WORK / "after-receive/snapshot.db")
    assert sum(key.startswith("diary:large-test-page-") for key in pages) == 12
    expected = json.loads((WORK / "expected-hashes.json").read_text())
    for digest in expected.values():
        # sha256sum runs in the Android test app UID, without pulling all photos into the host.
        result = adb("shell", "run-as", PACKAGES[1], "sha256sum", "files/photos/" + digest + ".bin").stdout.decode()
        assert result.split()[0] == digest
    original = documents(WORK / ("before-" + PACKAGES[1]) / "snapshot.db")
    for key, value in original.items():
        if key.startswith("diary:"):
            assert pages[key] == value, "Existing recipient pages must remain unchanged"
    print(json.dumps({"received_pages": 12, "received_photos": len(expected), "sha256": "all matched"}))
elif mode == "check-backup":
    expected = json.loads((WORK / "expected-hashes.json").read_text())
    archive = WORK / "large-photo-backup.zip"
    with zipfile.ZipFile(archive) as backup:
        assert backup.testzip() is None
        state = json.loads(backup.read("diary.json"))
        assert state.get("exchange") is None and not state["profile"]["lockEnabled"]
        assert not any(page["visibility"] == "PRIVATE" for page in state["diaries"])
        assert sum(page["id"].startswith("large-test-page-") for page in state["diaries"]) == 12
        for digest in expected.values():
            data = backup.read("photos/" + digest + ".bin")
            assert len(data) == 1024 * 1024 and hashlib.sha256(data).hexdigest() == digest
    result = {"fixture_pages": 12, "fixture_photos": len(expected), "original_bytes": len(expected) * 1024 * 1024,
              "backup_bytes": archive.stat().st_size, "sha256": "all matched", "private": "excluded", "pairing_secret": "excluded"}
    (WORK / "backup-result.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
elif mode == "restore":
    expected = json.loads((WORK / "expected-hashes.json").read_text())
    for package in PACKAGES:
        replace_database(package, WORK / ("before-" + package) / "snapshot.db")
        names = list(expected) if package == PACKAGES[0] else [digest + ".bin" for digest in expected.values()]
        for name in names:
            adb("shell", "run-as", package, "rm", "-f", "files/photos/" + name)
    print("Restored both isolated test databases; removed only generated fixture photos")
else:
    raise SystemExit("Use capture | fixture | install-fixture | check-received | check-backup | restore")
