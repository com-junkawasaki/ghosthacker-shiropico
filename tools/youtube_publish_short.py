#!/usr/bin/env python3
"""Publish localized SHIRO & PICO Shorts and captions to verified Yukkuri."""

import argparse
import asyncio
import json
import sys
from pathlib import Path

import requests
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials


EXPECTED_CHANNEL_ID = "UCTisE2aPQp3i8i6JUVIUoiw"
SCOPES = [
    "https://www.googleapis.com/auth/youtube.upload",
    "https://www.googleapis.com/auth/youtube.readonly",
    "https://www.googleapis.com/auth/youtube.force-ssl",
]

RELEASES = {
    "en": {
        "title": "The City Fell Asleep?! | SHIRO & PICO Ep. 1 #Shorts",
        "description": "SHIRO & PICO Episode 1 — a 60-second cyber anime short. Pico's Wild Ping wakes a threat; Shiro isolates the source with EDR. AI-assisted animation. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        "caption_name": "English",
    },
    "hi": {
        "title": "पूरा शहर सो गया?! | SHIRO & PICO एपिसोड 1 #Shorts",
        "description": "SHIRO & PICO एपिसोड 1 — 60 सेकंड की साइबर एनीमे शॉर्ट। पिको का Wild Ping खतरा जगा देता है और शिरो EDR से स्रोत को अलग करती है। AI-सहायता से निर्मित एनीमेशन। #SHIROandPICO #Cybersecurity #Anime #Shorts",
        "caption_name": "हिन्दी",
    },
    "ar": {
        "title": "نامت المدينة كلها؟! | SHIRO & PICO الحلقة 1 #Shorts",
        "description": "الحلقة الأولى من SHIRO & PICO — أنمي سيبراني في 60 ثانية. يوقظ Wild Ping تهديداً، وتعزل شيرو المصدر باستخدام EDR. رسوم متحركة بمساعدة الذكاء الاصطناعي. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        "caption_name": "العربية",
    },
}


def verify_channel(access_token: str) -> dict:
    response = requests.get(
        "https://www.googleapis.com/youtube/v3/channels",
        params={"part": "snippet", "mine": "true"},
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=30,
    )
    response.raise_for_status()
    items = response.json().get("items", [])
    if len(items) != 1 or items[0]["id"] != EXPECTED_CHANNEL_ID:
        raise RuntimeError(f"refusing unexpected YouTube channel: {items!r}")
    return {"id": items[0]["id"], "title": items[0]["snippet"]["title"]}


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--authorized-user", required=True)
    parser.add_argument("--media-dir", default="/tmp")
    parser.add_argument("--result", required=True)
    parser.add_argument("--library-src", required=True)
    args = parser.parse_args()

    sys.path.insert(0, str(Path(args.library_src).resolve()))
    from youtube_upload import upload_caption, upload_video

    credentials = Credentials.from_authorized_user_file(args.authorized_user, scopes=SCOPES)
    credentials.refresh(Request())
    channel = verify_channel(credentials.token)

    result_path = Path(args.result)
    result = json.loads(result_path.read_text()) if result_path.exists() else {
        "channel": channel,
        "privacy": "unlisted",
        "videos": {},
    }

    media_dir = Path(args.media_dir)
    for lang, metadata in RELEASES.items():
        if lang in result["videos"]:
            continue
        video_path = media_dir / f"shiropico-short-{lang}.mp4"
        caption_path = media_dir / f"shiropico-short-{lang}.srt"
        video_id = await upload_video(
            credentials.token,
            video_path.read_bytes(),
            title=metadata["title"],
            description=metadata["description"],
            tags=["SHIRO & PICO", "cybersecurity", "anime", "Shorts", "EDR"],
            category_id="24",
            default_language=lang,
            privacy_status="unlisted",
            made_for_kids=False,
        )
        result["videos"][lang] = {"id": video_id, "caption": "pending"}
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        try:
            await upload_caption(
                credentials.token,
                video_id,
                lang=lang,
                name=metadata["caption_name"],
                srt_bytes=caption_path.read_bytes(),
            )
            result["videos"][lang]["caption"] = "uploaded"
        except Exception as error:
            result["videos"][lang]["caption"] = f"failed: {error}"
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(f"uploaded {lang}: https://youtu.be/{video_id}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
