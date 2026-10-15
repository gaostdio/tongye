"""Create a source handoff using an explicit allowlist; Python stdlib only."""
import argparse
import hashlib
import json
import re
import shutil
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = (".gitignore", "AGENTS.md", "HANDOFF.md", "README.md", "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "build.ps1", "gradlew", "gradlew.bat")
SOURCE_DIRS = ("app/src", "app/schemas", "gradle", "docs", "backend", "tools")

def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()

def make_stage(reports_from=None):
    version = re.search(r'versionName\s*=\s*"([0-9A-Za-z._-]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
    stage = ROOT / ".handoff" / ("stage-" + uuid.uuid4().hex[:12]) / "tongye-project"
    stage.mkdir(parents=True)
    selected = {Path(name) for name in ROOT_FILES}
    selected.add(Path("app/build.gradle.kts"))
    for directory in SOURCE_DIRS:
        selected.update(path.relative_to(ROOT) for path in (ROOT / directory).rglob("*") if path.is_file() and "__pycache__" not in path.parts)
    for version_dir in dict.fromkeys(("0.2.0", "0.2.1", "0.2.2", "0.2.3", "0.2.4", "0.2.5", "0.2.6", version)):
        selected.update(path.relative_to(ROOT) for path in (ROOT / "deliverables/screenshots" / version_dir).glob("*.png"))
    selected.update((Path(f"deliverables/同页-{version}.apk"), Path(f"deliverables/同页-{version}.apk.sha256")))
    for name in ("migration-result.json", "continued-result.json"):
        history = Path("deliverables/verification/longevity") / name
        if (ROOT / history).is_file():
            selected.add(history)
    for relative in sorted(selected):
        source = ROOT / relative
        if not source.is_file():
            raise FileNotFoundError(source)
        if source.is_symlink() or not source.resolve().is_relative_to(ROOT):
            raise ValueError(f"Unexpected external source: {relative}")
        destination = stage / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    # Compact evidence, not generated classes, Gradle caches or emulator data.
    reports = stage / "deliverables/reports"
    reports.mkdir(parents=True)
    report_root = reports_from or ROOT / "app/build"
    for source in (report_root / "test-results/testDebugUnitTest").glob("TEST-*.xml"):
        destination = reports / "unit-tests" / source.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    for source in (report_root / "test-results/streamingLowMemory").glob("TEST-*.xml"):
        destination = reports / "low-memory" / source.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    lint = report_root / "reports/lint-results-debug.txt"
    if lint.is_file():
        shutil.copyfile(lint, reports / lint.name)
    return stage, version

def create_archive(stage, version, output):
    files = sorted(path for path in stage.rglob("*") if path.is_file() and path.name != "PACKAGE_MANIFEST.json")
    manifest = {"format": 1, "project": "Tongye", "version": version, "created_utc": datetime.now(timezone.utc).isoformat(),
                "manifest_note": "This manifest lists every packaged file except itself.",
                "excluded": ["build and Gradle caches", "local.properties", "SDK/JDK", "signing private keys", "old APKs", "emulator databases", "generated large test fixtures"],
                "files": [{"path": path.relative_to(stage).as_posix(), "bytes": path.stat().st_size, "sha256": digest(path)} for path in files]}
    manifest_path = stage / "PACKAGE_MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in files + [manifest_path]:
            entry = zipfile.ZipInfo.from_file(path, "tongye-project/" + path.relative_to(stage).as_posix())
            entry.create_system = 3
            entry.external_attr = (0o100755 if path.name == "gradlew" else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, path.read_bytes())
    checksum = output.with_suffix(output.suffix + ".sha256")
    checksum.write_text(digest(output) + "  " + output.name + "\n", encoding="utf-8")
    return {"zip": str(output), "stage": str(stage), "files": len(files) + 1, "zip_bytes": output.stat().st_size, "sha256": digest(output)}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Destination ZIP; default deliverables/同页-智能体交接包-<version>.zip")
    parser.add_argument("--reports-from", type=Path, help="Optional app/build directory of the freshly verified unpacked copy")
    args = parser.parse_args()
    stage, version = make_stage(args.reports_from)
    output = (args.output or ROOT / f"deliverables/同页-智能体交接包-{version}.zip").resolve()
    print(json.dumps(create_archive(stage, version, output), ensure_ascii=True))
