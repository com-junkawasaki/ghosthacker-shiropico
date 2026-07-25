#!/usr/bin/env python3
"""Change privacyStatus on the already-uploaded Shorts.

RULES.md makes going public an explicit-authorization step, so this is a
separate tool from the uploader rather than a flag on it, and it refuses to run
without --confirm.

videos.update REPLACES the whole `status` part: any field omitted from the
request reverts to its API default. Blindly PUTting {"privacyStatus": "public"}
would therefore silently clear selfDeclaredMadeForKids / embeddable / license.
So each video's current status is read first and only privacyStatus is changed.

Note madeForKids is read-only on the way out but must be written back as
selfDeclaredMadeForKids — they are different field names for the same setting.

Usage:
  python3 tools/youtube_set_privacy.py --authorized-user <token.json> \
    --result <upload-result.json> --privacy public --confirm
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import requests
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

EXPECTED_CHANNEL_ID = "UCTisE2aPQp3i8i6JUVIUoiw"
API = "https://www.googleapis.com/youtube/v3/videos"


def verify_channel(token: str) -> None:
    r = requests.get(
        "https://www.googleapis.com/youtube/v3/channels",
        params={"part": "id,snippet", "mine": "true"},
        headers={"Authorization": f"Bearer {token}"}, timeout=30)
    r.raise_for_status()
    items = r.json().get("items", [])
    if len(items) != 1 or items[0]["id"] != EXPECTED_CHANNEL_ID:
        raise SystemExit(f"channel mismatch: {[(i['id'], i['snippet']['title']) for i in items]}")
    print(f"channel verified: {items[0]['snippet']['title']} ({items[0]['id']})", flush=True)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--authorized-user", required=True)
    p.add_argument("--result", required=True)
    p.add_argument("--privacy", required=True, choices=["public", "unlisted", "private"])
    p.add_argument("--confirm", action="store_true",
                   help="required; without it nothing is changed")
    args = p.parse_args()

    creds = Credentials.from_authorized_user_file(args.authorized_user)
    creds.refresh(Request())
    verify_channel(creds.token)

    result_path = Path(args.result)
    result = json.loads(result_path.read_text(encoding="utf-8"))
    ids = [(ep, lang, e["id"])
           for ep in sorted(result["episodes"], key=int)
           for lang, e in result["episodes"][ep].items()]

    if not args.confirm:
        print(f"\nDRY RUN — would set {len(ids)} videos to '{args.privacy}':")
        for ep, lang, vid in ids:
            print(f"  ep{ep} {lang}  https://youtu.be/{vid}")
        print("\nRe-run with --confirm to apply.")
        return 0

    headers = {"Authorization": f"Bearer {creds.token}"}
    current = requests.get(API, params={"part": "status", "id": ",".join(v for _, _, v in ids)},
                           headers=headers, timeout=30)
    current.raise_for_status()
    status_by_id = {i["id"]: i["status"] for i in current.json().get("items", [])}

    failures = 0
    for ep, lang, vid in ids:
        st = status_by_id.get(vid)
        if st is None:
            failures += 1
            print(f"ep{ep} {lang}: FAILED — video not found", flush=True)
            continue
        body = {
            "id": vid,
            "status": {
                "privacyStatus": args.privacy,
                "license": st.get("license", "youtube"),
                "embeddable": st.get("embeddable", True),
                "publicStatsViewable": st.get("publicStatsViewable", True),
                "selfDeclaredMadeForKids": st.get("madeForKids", False),
            },
        }
        r = requests.put(API, params={"part": "status"}, headers=headers, json=body, timeout=60)
        if r.status_code != 200:
            failures += 1
            print(f"ep{ep} {lang}: FAILED {r.status_code} — {r.text[:160]}", flush=True)
            continue
        result["episodes"][ep][lang]["privacy"] = args.privacy
        print(f"ep{ep} {lang}: {args.privacy} -> https://youtu.be/{vid}", flush=True)

    result["privacy"] = args.privacy
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    print(f"done. failures={failures}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
