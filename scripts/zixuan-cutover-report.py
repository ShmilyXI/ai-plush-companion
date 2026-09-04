#!/usr/bin/env python3
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path


CONTRACT_VERSION = "zixuan-cutover-v1"
ROLLBACK_COMPONENTS = ("database", "redis", "storage", "proxy", "services", "artifacts")


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def directory_manifest(root: Path) -> dict:
    entries = []
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix()
        entries.append({"path": relative, "size": path.stat().st_size, "sha256": file_sha256(path)})
    canonical = "".join(f"{item['path']}\0{item['size']}\0{item['sha256']}\n" for item in entries)
    return {
        "root": str(root.resolve()),
        "fileCount": len(entries),
        "byteCount": sum(item["size"] for item in entries),
        "digest": hashlib.sha256(canonical.encode()).hexdigest(),
        "files": entries,
    }


def storage_command(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--target-root", type=Path, required=True)
    parser.add_argument("--snapshot-root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    source = args.source_root.resolve()
    target = args.target_root.resolve()
    snapshot_base = args.snapshot_root.resolve()
    if not source.is_dir() or source in (target, snapshot_base) or target == snapshot_base:
        parser.error("source, target, and snapshot roots must be distinct existing/safe paths")
    source_manifest = directory_manifest(source)
    report = {
        "contractVersion": CONTRACT_VERSION,
        "mode": "apply" if args.apply else "dry-run",
        "status": "planned",
        "source": source_manifest,
    }
    if not args.apply:
        write_json(args.report, report)
        return 0
    snapshot = snapshot_base / f"{source.name}.snapshot"
    snapshot_base.mkdir(parents=True, exist_ok=True)
    if not snapshot.exists():
        shutil.copytree(source, snapshot)
    if not target.exists():
        shutil.copytree(source, target)
    snapshot_manifest = directory_manifest(snapshot)
    target_manifest = directory_manifest(target)
    report.update({"snapshot": snapshot_manifest, "target": target_manifest})
    if not (source_manifest["digest"] == snapshot_manifest["digest"] == target_manifest["digest"]):
        report["status"] = "mismatch"
        write_json(args.report, report)
        return 2
    report["status"] = "ready"
    write_json(args.report, report)
    return 0


def aggregate_command(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("components", nargs="+")
    args = parser.parse_args(argv)
    components = []
    for raw in args.components:
        path = Path(raw)
        payload = json.loads(path.read_text(encoding="utf-8"))
        components.append({"path": str(path), "sha256": file_sha256(path), "report": payload})
    ready = all(
        item["report"].get("contractVersion") == args.contract_version
        and item["report"].get("status") in {"ready", "already-ready"}
        for item in components
    )
    output = {
        "contractVersion": args.contract_version,
        "status": "ready" if ready else "blocked",
        "components": components,
    }
    write_json(args.output, output)
    return 0 if ready else 2


def rollback_command(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("contractVersion") != CONTRACT_VERSION:
        raise SystemExit("rollback manifest contract version mismatch")
    rollback = manifest.get("rollback")
    if not isinstance(rollback, dict) or any(name not in rollback for name in ROLLBACK_COMPONENTS):
        raise SystemExit("rollback manifest is missing required components")
    plan = []
    for name in ROLLBACK_COMPONENTS:
        item = rollback[name]
        path = Path(item.get("path", ""))
        command = item.get("command")
        if not path.is_file() or file_sha256(path) != item.get("sha256"):
            raise SystemExit(f"rollback checksum mismatch: {name}")
        if not isinstance(command, list) or not command or not all(isinstance(part, str) and part for part in command):
            raise SystemExit(f"rollback command missing: {name}")
        plan.append({"component": name, "path": str(path), "command": command})
    if args.apply:
        for item in plan:
            subprocess.run(item["command"], check=True)
    print(json.dumps({"contractVersion": CONTRACT_VERSION, "mode": "apply" if args.apply else "dry-run", "plan": plan}, ensure_ascii=False))
    return 0


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "storage":
        return storage_command(sys.argv[2:])
    if len(sys.argv) > 1 and sys.argv[1] == "rollback":
        return rollback_command(sys.argv[2:])
    return aggregate_command(sys.argv[1:])


if __name__ == "__main__":
    raise SystemExit(main())
