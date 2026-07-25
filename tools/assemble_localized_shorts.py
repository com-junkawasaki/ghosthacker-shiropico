#!/usr/bin/env python3
"""Assemble Seedance clips and localized narration into Shorts masters."""

from __future__ import annotations

from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
RENDERS = ROOT / "shorts" / "renders"
MASTERS = ROOT / "shorts" / "masters"
VOICE = {"en": ("Samantha", "190"), "hi": ("Lekha", "175"), "ar": ("Majed", "185")}
TEXT = {
    2: {
        "en": "Pico cuts the power, but the malware comes right back. Shiro maps every persistence layer and removes the root. Rebooting is not remediation. Find every foothold, then remove it completely.",
        "hi": "पीको बिजली बंद करता है, लेकिन मालवेयर फिर लौट आता है। शिरो हर पर्सिस्टेंस लेयर खोजकर जड़ हटाती है। केवल रीबूट करना समाधान नहीं है। हर छिपे ठिकाने को खोजकर पूरी तरह हटाओ।",
        "ar": "يقطع بيكو الطاقة، لكن البرمجية الخبيثة تعود. ترسم شيرو كل طبقات الاستمرارية وتزيل الجذر. إعادة التشغيل ليست معالجة. اكتشف كل موطئ قدم ثم أزله بالكامل."
    },
    3: {
        "en": "Pico uses a vulnerability scanner to chase an attacker, and misses. A scanner finds holes in your own systems. Threat intelligence follows the adversary's clues. Different question, different tool.",
        "hi": "पीको हमलावर का पीछा करने के लिए वल्नरेबिलिटी स्कैनर चलाता है और चूक जाता है। स्कैनर आपके सिस्टम की कमजोरियां खोजता है। थ्रेट इंटेलिजेंस हमलावर के सुराग खोजती है। अलग सवाल, अलग औजार।",
        "ar": "يستخدم بيكو ماسح الثغرات لمطاردة المهاجم فيفشل. الماسح يجد الثقوب في أنظمتك، أما استخبارات التهديدات فتتبع أدلة الخصم. لكل سؤال أداة مختلفة."
    },
    4: {
        "en": "Pico clicks every alert until the noise becomes a forest. Shiro correlates related signals into real incidents. Then automation handles the routine response. Group first, automate second, investigate what matters.",
        "hi": "पीको हर अलर्ट पर क्लिक करता है और शोर जंगल बन जाता है। शिरो जुड़े संकेतों को असली इंसिडेंट में जोड़ती है। फिर ऑटोमेशन सामान्य प्रतिक्रिया संभालता है। पहले समूह बनाओ, फिर ऑटोमेट करो।",
        "ar": "ينقر بيكو كل تنبيه حتى تتحول الضوضاء إلى غابة. تربط شيرو الإشارات المتشابهة في حوادث حقيقية، ثم تتولى الأتمتة الاستجابة الروتينية. اجمع أولاً، ثم أتمت، وحقق فيما يهم."
    },
    5: {
        "en": "The visitor looks exactly like Shiro, so Pico lets it through. That is the trap. Zero Trust verifies identity, device, and context on every request. Familiar face or not: never trust, always verify.",
        "hi": "आगंतुक बिल्कुल शिरो जैसा दिखता है, इसलिए पीको उसे अंदर आने देता है। यही जाल है। ज़ीरो ट्रस्ट हर अनुरोध पर पहचान, डिवाइस और संदर्भ जांचता है। चेहरा परिचित हो फिर भी हमेशा सत्यापित करो।",
        "ar": "يبدو الزائر مثل شيرو تماماً، فيسمح له بيكو بالدخول. هذا هو الفخ. يتحقق نهج الثقة الصفرية من الهوية والجهاز والسياق في كل طلب. لا تثق بالمظهر المألوف، وتحقق دائماً."
    }
}
CLIPS = {
    2: ("ep02-reboot.mp4", "ep02-root-cut.mp4"),
    3: ("ep03-wrong-tool.mp4", "ep03-track-beast.mp4"),
    4: ("ep04-alert-forest.mp4", "ep04-correlate.mp4"),
    5: ("ep05-trusted-face.mp4", "ep05-verify-gate.mp4"),
}


def execute(command: list[str]) -> None:
    subprocess.run(command, check=True)


def main() -> None:
    MASTERS.mkdir(parents=True, exist_ok=True)
    for episode, clips in CLIPS.items():
        for language, narration in TEXT[episode].items():
            voice, rate = VOICE[language]
            spoken = MASTERS / f"ep{episode:02d}-{language}.aiff"
            output = MASTERS / f"shiropico-ep{episode:02d}-{language}.mp4"
            execute(["say", "-v", voice, "-r", rate, "-o", str(spoken), narration])
            execute([
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", str(RENDERS / clips[0]), "-i", str(RENDERS / clips[1]), "-i", str(spoken),
                "-filter_complex",
                "[0:v]setpts=PTS-STARTPTS[v0];[1:v]setpts=PTS-STARTPTS[v1];"
                "[v0][0:a][v1][1:a]concat=n=2:v=1:a=1[video][native];"
                "[native]volume=0.32[nativeq];[2:a]adelay=350|350,volume=1.35[voice];"
                "[nativeq][voice]amix=inputs=2:duration=first:dropout_transition=1[audio]",
                "-map", "[video]", "-map", "[audio]", "-c:v", "libx264", "-preset", "medium",
                "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart", "-t", "20.2", str(output),
            ])
            print(output)


if __name__ == "__main__":
    main()
