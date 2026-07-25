#!/usr/bin/env python3
"""Inventory + drift check for the ghosthacker-shiropico assets in B2.

Why this exists rather than a git-annex remote: the ai-gftd-datasets bucket
holds ~9.9 GB (297 objects) of SHIRO & PICO production assets — ep01-07 full
episodes in 11 languages, ep08-12 in ja, 178 scene stills, BGM, panels, the
ep01 motion comic. None of it belonged to any git repo or DataLad dataset, so
nothing could tell you whether it was still there; that is exactly the blind
spot that let ep01 be declared lost (ADR-2607252000).

git-annex cannot adopt these bytes cheaply:
  - `git annex import --from <s3remote> --no-content` is rejected: "This remote
    does not support importing without downloading content", so an import means
    pulling all 9.9 GB to hash it.
  - Constructing SHA1 keys from the listing instead would be unsound: every
    object's contentSha1 is prefixed `unverified:` (uploaded through the S3
    API, where B2 does not check the hash). Trusting those would manufacture
    annex entries claiming content that might not verify on `get` — a fake
    backup, the precise failure this whole effort exists to prevent.

So custody here is *inventory + drift detection*, not content addressing: we
record exactly what is in the bucket and can prove, at any time and at ~zero
transfer, that it is all still present and unchanged. Retrieval remains a
plain download.

Credentials come from Keychain service `b2:ai-gftd-datasets` (account = key id,
password = app key) or B2_KEY_ID / B2_APP_KEY in the environment. The key is
scoped to this bucket only.

Usage:
  python3 tools/b2_catalog.py --generate    # refresh catalog/ai-gftd-datasets.json
  python3 tools/b2_catalog.py --verify      # exit 1 on any drift
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "catalog" / "ai-gftd-datasets.json"
BUCKET = "ai-gftd-datasets"
PREFIX = "ghosthacker-shiropico/"
KEYCHAIN_SERVICE = "b2:ai-gftd-datasets"


def credentials() -> tuple[str, str]:
    kid, key = os.environ.get("B2_KEY_ID"), os.environ.get("B2_APP_KEY")
    if kid and key:
        return kid, key
    try:
        out = subprocess.run(
            ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-g"],
            check=True, capture_output=True, text=True)
        # -g prints the account on stdout and the password on stderr
        acct = next(l.split('"')[3] for l in out.stdout.splitlines() if '"acct"' in l)
        pw = out.stderr.split('password: "')[1].split('"')[0]
        return acct, pw
    except Exception as exc:  # noqa: BLE001
        raise SystemExit(
            f"B2 credentials unavailable (env B2_KEY_ID/B2_APP_KEY or Keychain "
            f"{KEYCHAIN_SERVICE}): {exc}")


def api_call(url: str, token: str | None, payload: dict | None) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = token
    req = urllib.request.Request(url, data=data, headers=headers)
    return json.load(urllib.request.urlopen(req))


def list_objects() -> list[dict]:
    kid, key = credentials()
    basic = base64.b64encode(f"{kid}:{key}".encode()).decode()
    auth = json.load(urllib.request.urlopen(urllib.request.Request(
        "https://api.backblazeb2.com/b2api/v3/b2_authorize_account",
        headers={"Authorization": f"Basic {basic}"})))
    api, token = auth["apiInfo"]["storageApi"]["apiUrl"], auth["authorizationToken"]
    buckets = api_call(f"{api}/b2api/v3/b2_list_buckets", token,
                       {"accountId": auth["accountId"], "bucketName": BUCKET})
    bid = buckets["buckets"][0]["bucketId"]

    files, start = [], None
    while True:
        page = api_call(f"{api}/b2api/v3/b2_list_file_names", token,
                        {"bucketId": bid, "prefix": PREFIX, "maxFileCount": 1000,
                         "startFileName": start})
        files.extend(page.get("files", []))
        start = page.get("nextFileName")
        if not start:
            break
    return [
        {"path": f["fileName"][len(PREFIX):],
         "size": f["contentLength"],
         "sha1": f.get("contentSha1"),
         "uploaded": f.get("uploadTimestamp")}
        for f in sorted(files, key=lambda x: x["fileName"])
    ]


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--generate", action="store_true")
    p.add_argument("--verify", action="store_true")
    args = p.parse_args()
    if not (args.generate or args.verify):
        p.error("one of --generate / --verify is required")

    live = list_objects()
    total = sum(o["size"] for o in live)

    if args.generate:
        CATALOG.parent.mkdir(parents=True, exist_ok=True)
        CATALOG.write_text(json.dumps(
            {"bucket": BUCKET, "prefix": PREFIX,
             "note": "Inventory only — bytes are not annexed. See tools/b2_catalog.py.",
             "files": len(live), "bytes": total, "objects": live},
            ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"catalog written: {len(live)} files, {total/1048576:.1f} MB -> {CATALOG}")
        return 0

    if not CATALOG.exists():
        raise SystemExit(f"no catalog at {CATALOG}; run --generate first")
    recorded = json.loads(CATALOG.read_text(encoding="utf-8"))
    was = {o["path"]: o for o in recorded["objects"]}
    now = {o["path"]: o for o in live}

    missing = sorted(set(was) - set(now))
    added = sorted(set(now) - set(was))
    changed = sorted(k for k in set(was) & set(now)
                     if was[k]["size"] != now[k]["size"] or was[k]["sha1"] != now[k]["sha1"])

    print(f"b2-catalog verify: recorded={len(was)} live={len(now)} "
          f"missing={len(missing)} changed={len(changed)} new={len(added)}")
    for path in missing[:20]:
        print(f"  MISSING: {path} ({was[path]['size']} bytes)")
    for path in changed[:20]:
        print(f"  CHANGED: {path} {was[path]['size']}->{now[path]['size']}")
    for path in added[:20]:
        print(f"  new (not yet catalogued): {path}")

    if missing or changed:
        print("b2-catalog verify: FAIL", file=sys.stderr)
        return 1
    print("b2-catalog verify: OK" + (" (new objects present; re-run --generate)" if added else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
