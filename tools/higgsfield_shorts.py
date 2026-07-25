#!/usr/bin/env python3
"""Generate SHIRO & PICO short clips from the checked-in Higgsfield manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "shorts" / "episodes-02-05-higgsfield.edn"


def read_edn(path: Path) -> dict:
    expression = (
        "(require '[clojure.edn :as edn] '[cheshire.core :as json]) "
        "(print (json/generate-string (edn/read-string (slurp (first *command-line-args*)))))"
    )
    result = subprocess.run(
        ["bb", "-e", expression, str(path)], check=True, text=True, capture_output=True
    )
    return json.loads(result.stdout)


def write_edn(path: Path, value: dict) -> None:
    expression = (
        "(require '[cheshire.core :as json] '[clojure.pprint :as pp]) "
        "(with-open [w (clojure.java.io/writer (first *command-line-args*))] "
        "(binding [*out* w] (pp/pprint (json/parse-string (slurp *in*) true))))"
    )
    subprocess.run(
        ["bb", "-e", expression, str(path)],
        input=json.dumps(value, ensure_ascii=False), check=True, text=True
    )


def run_json(command: list[str]) -> dict:
    result = subprocess.run(command + ["--json"], check=True, text=True, capture_output=True)
    payload = json.loads(result.stdout)
    if isinstance(payload, list):
        if len(payload) != 1:
            raise ValueError(f"expected one Higgsfield job, got {len(payload)}")
        return payload[0]
    return payload


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("keyframes", "clips"))
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--ledger", type=Path, default=ROOT / "shorts" / "higgsfield-jobs.edn")
    args = parser.parse_args()
    spec = read_edn(args.manifest)
    ledger = read_edn(args.ledger) if args.ledger.exists() else {"jobs": {}}
    output_dir = ROOT / "shorts" / "renders"
    output_dir.mkdir(parents=True, exist_ok=True)

    for episode in spec["episodes"]:
        for shot in episode["shots"]:
            key = f"ep{episode['episode']:02d}-{shot['id']}"
            record = ledger["jobs"].setdefault(key, {})
            if args.stage == "keyframes":
                if record.get("keyframe_url"):
                    continue
                job = run_json([
                    "higgsfield", "generate", "create", "nano_banana_2_lite",
                    "--prompt", shot["keyframe"], "--aspect-ratio", "9:16",
                    "--thinking", "HIGH", "--image-references", spec["characterReference"],
                    "--wait", "--wait-timeout", "20m", "--wait-interval", "5s",
                ])
                record["keyframe_job"] = job.get("id")
                record["keyframe_url"] = job.get("result_url")
                path = output_dir / f"{key}.png"
                urllib.request.urlretrieve(record["keyframe_url"], path)
                record["keyframe_path"] = str(path)
            else:
                if record.get("clip_url"):
                    continue
                if not record.get("keyframe_path"):
                    raise SystemExit(f"missing keyframe for {key}")
                job = run_json([
                    "higgsfield", "generate", "create", "seedance_2_0",
                    "--prompt", shot["motion"], "--aspect-ratio", "9:16",
                    "--duration", str(spec["format"]["clipSeconds"]),
                    "--generate-audio", "true", "--mode", "fast", "--resolution", "480p",
                    "--start-image", record["keyframe_path"],
                    "--wait", "--wait-timeout", "30m", "--wait-interval", "10s",
                ])
                record["clip_job"] = job.get("id")
                record["clip_url"] = job.get("result_url")
                path = output_dir / f"{key}.mp4"
                urllib.request.urlretrieve(record["clip_url"], path)
                record["clip_path"] = str(path)
            write_edn(args.ledger, ledger)
            print(f"completed {args.stage}: {key}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr, file=sys.stderr)
        raise
