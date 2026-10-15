"""Verify handoff CRCs, exact inventory and file hashes, optionally unpack safely."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path, PurePosixPath

def verify(path, unpack_to=None):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate entries"
        for name in names:
            parts = PurePosixPath(name).parts
            assert parts and parts[0] == "tongye-project" and ".." not in parts and not name.startswith("/") and "\\" not in name and ":" not in name
            assert "local.properties" not in parts and ".gradle" not in parts and "build" not in parts and "__pycache__" not in parts
            assert not name.endswith((".jks", ".keystore", ".db", ".db-wal", ".db-shm", ".couple"))
        assert archive.testzip() is None, "ZIP CRC mismatch"
        manifest_name = "tongye-project/PACKAGE_MANIFEST.json"
        manifest = json.loads(archive.read(manifest_name))
        expected = {"tongye-project/" + record["path"] for record in manifest["files"]} | {manifest_name}
        assert set(names) == expected, "Archive inventory mismatch"
        for record in manifest["files"]:
            data = archive.read("tongye-project/" + record["path"])
            assert len(data) == record["bytes"]
            assert hashlib.sha256(data).hexdigest() == record["sha256"], record["path"]
        for required in ("AGENTS.md", "HANDOFF.md", "README.md", "build.ps1", "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "app/src/main/AndroidManifest.xml", "app/schemas/app.tongye.diary.DiaryDatabase/1.json", "app/schemas/app.tongye.diary.DiaryDatabase/2.json"):
            assert "tongye-project/" + required in expected, required
        apk_name = "tongye-project/deliverables/同页-" + manifest["version"] + ".apk"
        assert apk_name in expected
        apk_hash = hashlib.sha256(archive.read(apk_name)).hexdigest()
        sidecar_hash = archive.read(apk_name + ".sha256").decode("utf-8-sig").split()[0]
        assert apk_hash == sidecar_hash, "APK checksum sidecar mismatch"
        if unpack_to:
            unpack_to = unpack_to.resolve()
            assert not unpack_to.exists(), "Use a new unpack directory"
            unpack_to.mkdir(parents=True)
            for name in names:
                target = (unpack_to / name).resolve()
                assert target.is_relative_to(unpack_to)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
                target.chmod((archive.getinfo(name).external_attr >> 16) & 0o777)
        return {"version": manifest["version"], "files": len(names), "crc": "passed", "sha256_per_file": "passed", "unpacked": str(unpack_to / "tongye-project") if unpack_to else None}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--unpack-to", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.archive, args.unpack_to), ensure_ascii=True))
