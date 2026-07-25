#!/usr/bin/env python3
"""Attach SRT captions to the already-uploaded ep02-05 Shorts.

youtube_publish_episodes.py uploaded the videos with no captions because no SRT
existed for ep02-05. tools/make_captions.py now generates them with measured
timings, and this script attaches them via captions.insert.

captions.insert needs the youtube.force-ssl scope. The token stored before
2026-07-25 only carried youtube.upload + youtube.readonly, so this would have
failed with invalid_scope; the re-authorized token has all three.

Re-running is safe in the sense that it never overwrites silently: YouTube
rejects a second track for the same language with a 409-style error, which is
reported per item rather than aborting the run.

Usage:
  python3 tools/youtube_attach_captions.py \
    --authorized-user <token.json> --result <upload-result.json> \
    --captions-dir shorts/captions --library-src <youtube-upload/src>
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

import requests
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials

EXPECTED_CHANNEL_ID = "UCTisE2aPQp3i8i6JUVIUoiw"
CAPTION_TRACK_NAME = {"en": "English", "hi": "हिन्दी", "ar": "العربية"}


def verify_channel(access_token: str) -> None:
    response = requests.get(
        "https://www.googleapis.com/youtube/v3/channels",
        params={"part": "id,snippet", "mine": "true"},
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=30,
    )
    response.raise_for_status()
    items = response.json().get("items", [])
    if len(items) != 1 or items[0]["id"] != EXPECTED_CHANNEL_ID:
        found = [(i["id"], i["snippet"]["title"]) for i in items]
        raise SystemExit(f"channel mismatch: expected {EXPECTED_CHANNEL_ID}, got {found}")
    print(f"channel verified: {items[0]['snippet']['title']} ({items[0]['id']})", flush=True)


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--authorized-user", required=True)
    parser.add_argument("--result", required=True)
    parser.add_argument("--captions-dir", required=True)
    parser.add_argument("--library-src", required=True)
    args = parser.parse_args()

    sys.path.insert(0, args.library_src)
    from youtube_upload.client import upload_caption  # noqa: E402

    credentials = Credentials.from_authorized_user_file(args.authorized_user)
    credentials.refresh(Request())
    verify_channel(credentials.token)

    result_path = Path(args.result)
    result = json.loads(result_path.read_text(encoding="utf-8"))
    captions_dir = Path(args.captions_dir)

    failures = 0
    for episode in sorted(result["episodes"], key=int):
        for language, entry in result["episodes"][episode].items():
            video_id = entry["id"]
            srt = captions_dir / f"shiropico-ep{int(episode):02d}-{language}.srt"
            if not srt.exists():
                print(f"ep{episode} {language}: SKIP (no {srt.name})", flush=True)
                continue
            try:
                await upload_caption(
                    credentials.token,
                    video_id,
                    lang=language,
                    name=CAPTION_TRACK_NAME.get(language, language),
                    srt_bytes=srt.read_bytes(),
                )
            except Exception as exc:  # noqa: BLE001 - report per item, keep going
                failures += 1
                entry["caption"] = f"failed: {exc}"[:200]
                print(f"ep{episode} {language}: FAILED — {exc}", flush=True)
            else:
                entry["caption"] = language
                print(f"ep{episode} {language}: attached -> https://youtu.be/{video_id}", flush=True)
            result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                   encoding="utf-8")

    print(f"done. failures={failures}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
