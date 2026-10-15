"""Developer-only fixtures and checks; never targets a physical phone."""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
fixture_database = root / "deliverables/verification/longevity/legacy-10000.db"
if sys.argv[1] == "pull":
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    adb_name = "adb.exe" if os.name == "nt" else "adb"
    adb = Path(sdk) / "platform-tools" / adb_name if sdk else Path(shutil.which(adb_name) or str(Path.home() / ".codex/android-runtime/focusmirror/sdk/platform-tools" / adb_name))
    avd = subprocess.check_output([str(adb), "-s", "emulator-5554", "emu", "avd", "name"], text=True)
    assert avd.splitlines()[0].strip() == "tongye_test"
    directory = Path(sys.argv[2])
    directory.mkdir(parents=True, exist_ok=True)
    for name in ("tongye.db", "tongye.db-wal", "tongye.db-shm"):
        result = subprocess.run([str(adb), "-s", "emulator-5554", "exec-out", "run-as", "app.tongye.diary", "cat", "databases/" + name], capture_output=True)
        if result.returncode == 0:
            (directory / name).write_bytes(result.stdout)
    print(str(directory / "tongye.db"))
elif sys.argv[1] == "fixture":
    schema = json.loads((root / "app/schemas/app.tongye.diary.DiaryDatabase/1.json").read_text())
    payload = (root / "deliverables/verification/exchange/long-term-legacy.json").read_text(encoding="utf-8")
    target = fixture_database
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()
    with sqlite3.connect(target) as connection:
        for entity in schema["database"]["entities"]:
            connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for query in schema["database"]["setupQueries"]:
            connection.execute(query)
        connection.execute("INSERT INTO workspace(id,json) VALUES(1,?)", (payload,))
        connection.execute("PRAGMA user_version=1")
    print(json.dumps({"legacy_json_bytes": len(payload.encode()), "diaries": len(json.loads(payload)["diaries"])}, ensure_ascii=False))
else:
    with sqlite3.connect(sys.argv[2]) as connection:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        assert version == 2, version
        rows = connection.execute("SELECT documentId,chunkIndex,json FROM workspace_documents ORDER BY documentId,chunkIndex").fetchall()
        documents = {}
        for key, index, data in rows:
            parts = documents.setdefault(key, [])
            assert index == len(parts)
            assert len(data.encode()) < 256 * 1024
            parts.append(data)
        state = json.loads("".join(documents["workspace"]))
        pages = {key[6:]: json.loads("".join(parts)) for key, parts in documents.items() if key.startswith("diary:")}
        legacy = connection.execute("SELECT count(*) FROM workspace").fetchone()[0]
        assert legacy == 0, "legacy row must only be cleared after the new documents commit"
        if sys.argv[1] in ("migrated", "continued"):
            with sqlite3.connect(fixture_database) as fixture_connection:
                fixture = json.loads(fixture_connection.execute("SELECT json FROM workspace WHERE id=1").fetchone()[0])
        if sys.argv[1] == "migrated":
            assert len(pages) == 10_000
            assert pages == {page["id"]: page for page in fixture["diaries"]}
            assert state["exchange"]["userId"] == fixture["exchange"]["userId"]
            assert state["profile"] == fixture["profile"]
        elif sys.argv[1] == "continued":
            assert len(pages) == 10_001
            assert any(page["title"] == "Long-term-next-day" for page in pages.values())
            assert "_edited_after_years" in pages["long-2"]["body"]
            assert sum(page["visibility"] == "PRIVATE" for page in pages.values()) == 1_000
            for old in fixture["diaries"]:
                if old["id"] != "long-2":
                    assert pages[old["id"]] == old
            assert pages["long-2"]["body"].replace("_edited_after_years", "") == next(page["body"] for page in fixture["diaries"] if page["id"] == "long-2")
        elif sys.argv[1] == "small":
            with sqlite3.connect(sys.argv[3]) as old_connection:
                previous = json.loads(old_connection.execute("SELECT json FROM workspace WHERE id=1").fetchone()[0])
            assert pages == {page["id"]: page for page in previous["diaries"]}
            assert state == dict(previous, diaries=[])
        print(json.dumps({"schema": version, "diaries": len(pages), "chunks": len(rows), "largest_row_bytes": max(len(row[2].encode()) for row in rows), "identity": state["exchange"]["userId"]}, ensure_ascii=False))
