#!/usr/bin/env python3
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


CONTRACT_VERSION = "zixuan-cutover-v1"
TTL_TOLERANCE_MS = 1000


def cli(args, *, data=None, text=True, check=True):
    return subprocess.run(args, input=data, capture_output=True, text=text, check=check)


def ttl_matches(source_pttl: int, target_pttl: int) -> bool:
    if source_pttl == -1 or target_pttl == -1:
        return source_pttl == target_pttl
    return source_pttl >= 0 and target_pttl >= 0 and abs(source_pttl - target_pttl) <= TTL_TOLERANCE_MS


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True)
    parser.add_argument("--container")
    parser.add_argument("--source-prefix", required=True)
    parser.add_argument("--target-prefix", required=True)
    parser.add_argument("--include-prefix", action="append", required=True)
    parser.add_argument("--transient-prefix", action="append", default=[])
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--inject-failure-after", type=int)
    args = parser.parse_args()
    if not args.target_prefix or args.source_prefix == args.target_prefix:
        parser.error("target prefix must be non-empty and differ from the source prefix")
    base = (["docker", "exec", "-i", args.container, "redis-cli"] if args.container
            else ["redis-cli", "-h", args.host, "-p", str(args.port)])
    scanned = cli(base + ["--raw", "--scan", "--pattern", f"{args.source_prefix}*"]).stdout.splitlines()
    selected = []
    skipped = []
    for key in sorted(scanned):
        suffix = key[len(args.source_prefix):]
        if any(suffix.startswith(prefix) for prefix in args.transient_prefix):
            skipped.append({"key": key, "reason": "transient"})
        elif any(suffix.startswith(prefix) for prefix in args.include_prefix):
            selected.append((key, f"{args.target_prefix}{suffix}"))
        else:
            skipped.append({"key": key, "reason": "outside-business-prefixes"})
    entries = []
    conflicts = []
    copied = 0
    unchanged = 0
    for source_key, target_key in selected:
        dumped = cli(base + ["--raw", "DUMP", source_key], text=False).stdout
        if dumped.endswith(b"\n"):
            dumped = dumped[:-1]
        key_type = cli(base + ["--raw", "TYPE", source_key]).stdout.strip()
        pttl = int(cli(base + ["--raw", "PTTL", source_key]).stdout.strip())
        digest = hashlib.sha256(dumped).hexdigest()
        entry = {"source": source_key, "target": target_key, "type": key_type, "pttl": pttl, "sha256": digest}
        entries.append(entry)
        if not args.apply:
            continue
        exists = cli(base + ["--raw", "EXISTS", target_key]).stdout.strip() == "1"
        if exists:
            target_dump = cli(base + ["--raw", "DUMP", target_key], text=False).stdout
            if target_dump.endswith(b"\n"):
                target_dump = target_dump[:-1]
            target_digest = hashlib.sha256(target_dump).hexdigest()
            target_type = cli(base + ["--raw", "TYPE", target_key]).stdout.strip()
            target_pttl = int(cli(base + ["--raw", "PTTL", target_key]).stdout.strip())
            if target_digest != digest:
                conflicts.append({"source": source_key, "target": target_key, "reason": "digest-mismatch"})
                continue
            if target_type != key_type:
                conflicts.append({"source": source_key, "target": target_key, "reason": "type-mismatch"})
                continue
            if not ttl_matches(pttl, target_pttl):
                conflicts.append({
                    "source": source_key,
                    "target": target_key,
                    "reason": "ttl-mismatch",
                    "sourcePttl": pttl,
                    "targetPttl": target_pttl,
                })
                continue
            unchanged += 1
            continue
        restore_ttl = max(pttl, 0)
        cli(base + ["-x", "RESTORE", target_key, str(restore_ttl)], data=dumped, text=False)
        copied += 1
        if args.inject_failure_after is not None and copied >= args.inject_failure_after:
            raise RuntimeError("injected redis migration failure")
    report = {
        "contractVersion": CONTRACT_VERSION,
        "mode": "apply" if args.apply else "dry-run",
        "status": "conflict" if conflicts else "ready",
        "sourcePrefix": args.source_prefix,
        "targetPrefix": args.target_prefix,
        "entries": entries,
        "skipped": skipped,
        "copied": copied,
        "unchanged": unchanged,
        "conflicts": conflicts,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 2 if conflicts else 0


if __name__ == "__main__":
    raise SystemExit(main())
