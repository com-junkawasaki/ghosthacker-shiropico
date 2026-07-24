#!/usr/bin/env python3
"""Publish localized SHIRO & PICO episode Shorts (ep02-05) to the verified channel.

Companion to youtube_publish_short.py (which only covers the Ep.1 pilot
release, media named shiropico-short-{lang}.mp4). Episodes 2-5 were rendered
via the Seedance/Higgsfield shot pipeline (shorts/episodes-02-05-higgsfield.edn)
and assembled into shorts/masters/shiropico-ep{NN}-{lang}.mp4 by
assemble_localized_shorts.py. No SRT captions exist yet for these episodes, so
caption upload is skipped (unlike Ep.1's script).
"""

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

TAGS = ["SHIRO & PICO", "cybersecurity", "anime", "Shorts"]

EPISODES = {
    2: {
        "en": {
            "title": "It Keeps Coming Back?! | SHIRO & PICO Ep. 2 #Shorts",
            "description": "SHIRO & PICO Episode 2 — a 20-second cyber anime short. Pico force-reboots the factory and the Daemon comes right back; Shiro maps every persistence layer and removes the root. Rebooting is not remediation. AI-assisted animation. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "hi": {
            "title": "यह फिर लौट आता है?! | SHIRO & PICO एपिसोड 2 #Shorts",
            "description": "SHIRO & PICO एपिसोड 2 — 20 सेकंड की साइबर एनीमे शॉर्ट। पीको बिजली बंद करता है, लेकिन मालवेयर फिर लौट आता है। शिरो हर पर्सिस्टेंस लेयर खोजकर जड़ हटाती है। केवल रीबूट करना समाधान नहीं है। AI-सहायता से निर्मित एनीमेशन। #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "ar": {
            "title": "إنه يعود مجدداً؟! | SHIRO & PICO الحلقة 2 #Shorts",
            "description": "الحلقة الثانية من SHIRO & PICO — أنمي سيبراني في 20 ثانية. يقطع بيكو الطاقة، لكن البرمجية الخبيثة تعود. ترسم شيرو كل طبقات الاستمرارية وتزيل الجذر. إعادة التشغيل ليست معالجة. رسوم متحركة بمساعدة الذكاء الاصطناعي. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
    },
    3: {
        "en": {
            "title": "Wrong Tool, Wrong Beast?! | SHIRO & PICO Ep. 3 #Shorts",
            "description": "SHIRO & PICO Episode 3 — a 20-second cyber anime short. Pico uses a vulnerability scanner to chase an attacker, and misses completely. A scanner finds holes in your own systems; threat intelligence follows the adversary's clues. AI-assisted animation. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "hi": {
            "title": "गलत औज़ार, गलत निशाना?! | SHIRO & PICO एपिसोड 3 #Shorts",
            "description": "SHIRO & PICO एपिसोड 3 — 20 सेकंड की साइबर एनीमे शॉर्ट। पीको हमलावर का पीछा करने के लिए वल्नरेबिलिटी स्कैनर चलाता है और चूक जाता है। स्कैनर आपके सिस्टम की कमजोरियां खोजता है, थ्रेट इंटेलिजेंस हमलावर के सुराग खोजती है। AI-सहायता से निर्मित एनीमेशन। #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "ar": {
            "title": "أداة خاطئة، هدف خاطئ؟! | SHIRO & PICO الحلقة 3 #Shorts",
            "description": "الحلقة الثالثة من SHIRO & PICO — أنمي سيبراني في 20 ثانية. يستخدم بيكو ماسح الثغرات لمطاردة المهاجم فيفشل تماماً. الماسح يجد الثقوب في أنظمتك، أما استخبارات التهديدات فتتبع أدلة الخصم. رسوم متحركة بمساعدة الذكاء الاصطناعي. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
    },
    4: {
        "en": {
            "title": "Lost in the Alert Forest?! | SHIRO & PICO Ep. 4 #Shorts",
            "description": "SHIRO & PICO Episode 4 — a 20-second cyber anime short. Pico clicks every alert until the noise becomes a forest; Shiro correlates related signals into real incidents, then automation handles the routine response. AI-assisted animation. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "hi": {
            "title": "अलर्ट के जंगल में खो गए?! | SHIRO & PICO एपिसोड 4 #Shorts",
            "description": "SHIRO & PICO एपिसोड 4 — 20 सेकंड की साइबर एनीमे शॉर्ट। पीको हर अलर्ट पर क्लिक करता है और शोर जंगल बन जाता है। शिरो जुड़े संकेतों को असली इंसिडेंट में जोड़ती है, फिर ऑटोमेशन सामान्य प्रतिक्रिया संभालता है। AI-सहायता से निर्मित एनीमेशन। #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "ar": {
            "title": "تائه في غابة التنبيهات؟! | SHIRO & PICO الحلقة 4 #Shorts",
            "description": "الحلقة الرابعة من SHIRO & PICO — أنمي سيبراني في 20 ثانية. ينقر بيكو كل تنبيه حتى تتحول الضوضاء إلى غابة. تربط شيرو الإشارات المتشابهة في حوادث حقيقية، ثم تتولى الأتمتة الاستجابة الروتينية. رسوم متحركة بمساعدة الذكاء الاصطناعي. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
    },
    5: {
        "en": {
            "title": "One Gate, Everyone Gets Checked?! | SHIRO & PICO Ep. 5 #Shorts",
            "description": "SHIRO & PICO Episode 5 — a 20-second cyber anime short. The visitor looks exactly like Shiro, so Pico lets it through — that is the trap. Zero Trust verifies identity, device, and context on every request. AI-assisted animation. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "hi": {
            "title": "एक दरवाज़ा, सबकी जांच?! | SHIRO & PICO एपिसोड 5 #Shorts",
            "description": "SHIRO & PICO एपिसोड 5 — 20 सेकंड की साइबर एनीमे शॉर्ट। आगंतुक बिल्कुल शिरो जैसा दिखता है, इसलिए पीको उसे अंदर आने देता है — यही जाल है। ज़ीरो ट्रस्ट हर अनुरोध पर पहचान, डिवाइस और संदर्भ जांचता है। AI-सहायता से निर्मित एनीमेशन। #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
        "ar": {
            "title": "بوابة واحدة، الجميع يُفحص؟! | SHIRO & PICO الحلقة 5 #Shorts",
            "description": "الحلقة الخامسة من SHIRO & PICO — أنمي سيبراني في 20 ثانية. يبدو الزائر مثل شيرو تماماً، فيسمح له بيكو بالدخول — هذا هو الفخ. يتحقق نهج الثقة الصفرية من الهوية والجهاز والسياق في كل طلب. رسوم متحركة بمساعدة الذكاء الاصطناعي. #SHIROandPICO #Cybersecurity #Anime #Shorts",
        },
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
    from youtube_upload import upload_video

    credentials = Credentials.from_authorized_user_file(args.authorized_user, scopes=SCOPES)
    credentials.refresh(Request())
    channel = verify_channel(credentials.token)

    result_path = Path(args.result)
    result = json.loads(result_path.read_text()) if result_path.exists() else {
        "channel": channel,
        "privacy": "unlisted",
        "episodes": {},
    }

    media_dir = Path(args.media_dir)
    for episode, releases in EPISODES.items():
        ep_key = str(episode)
        result["episodes"].setdefault(ep_key, {})
        for lang, metadata in releases.items():
            if lang in result["episodes"][ep_key]:
                continue
            video_path = media_dir / f"shiropico-ep{episode:02d}-{lang}.mp4"
            video_id = await upload_video(
                credentials.token,
                video_path.read_bytes(),
                title=metadata["title"],
                description=metadata["description"],
                tags=TAGS,
                category_id="24",
                default_language=lang,
                privacy_status="unlisted",
                made_for_kids=False,
            )
            result["episodes"][ep_key][lang] = {"id": video_id, "caption": "none"}
            result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
            print(f"uploaded ep{episode:02d} {lang}: https://youtu.be/{video_id}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
