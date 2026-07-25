#!/usr/bin/env python3
"""Generate SRT captions for the ep02-05 localized Shorts.

Timings are measured, not guessed. `say` gives no word/sentence timing, so for
each sentence boundary i we synthesize the *cumulative prefix* (sentences 1..i)
with the same voice and rate the master used, and take that clip's duration as
sentence i's end time. Summing independently-synthesized sentences would be
wrong: it double-counts each clip's leading/trailing silence and drops the
inter-sentence pauses `say` inserts inside a single utterance.

The narration is mixed into the master with `adelay=350` (see
assemble_localized_shorts.py), so every cue is shifted by that offset, and cues
are clipped to the master's `-t 20.2` cutoff.

Self-check: the final cumulative duration must land close to the already-rendered
`ep{NN}-{lang}.aiff`. A large gap means the prefix trick stopped matching how
`say` renders the full text, and the timings should not be trusted.

Usage:
  python3 tools/make_captions.py [--out-dir shorts/captions] [--tolerance 0.6]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from assemble_localized_shorts import CLIPS, MASTERS, TEXT, VOICE

ROOT = Path(__file__).resolve().parents[1]
NARRATION_OFFSET = 0.350   # adelay=350|350
MASTER_CUTOFF = 20.2       # ffmpeg -t 20.2

# Sentence terminators: ASCII full stop (en/ar) and Devanagari danda (hi).
SENTENCE_RE = re.compile(r"[^.।]+[.।]")


def split_sentences(text: str) -> list[str]:
    parts = [m.group(0).strip() for m in SENTENCE_RE.finditer(text)]
    consumed = sum(len(m.group(0)) for m in SENTENCE_RE.finditer(text))
    tail = text[consumed:].strip()
    if tail:
        parts.append(tail)
    return parts


def spoken_duration(text: str, voice: str, rate: str) -> float:
    with tempfile.NamedTemporaryFile(suffix=".aiff", delete=True) as tmp:
        subprocess.run(["say", "-v", voice, "-r", rate, "-o", tmp.name, text], check=True)
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", tmp.name],
            check=True, capture_output=True, text=True)
    return float(out.stdout.strip())


def srt_timestamp(seconds: float) -> str:
    if seconds < 0:
        seconds = 0.0
    ms = int(round(seconds * 1000))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def build_srt(sentences: list[str], ends: list[float]) -> str:
    blocks = []
    index = 0
    start = NARRATION_OFFSET
    for sentence, end in zip(sentences, ends):
        cue_end = min(end + NARRATION_OFFSET, MASTER_CUTOFF)
        if cue_end <= start:
            continue
        index += 1
        blocks.append(
            f"{index}\n{srt_timestamp(start)} --> {srt_timestamp(cue_end)}\n{sentence}\n")
        start = cue_end
    return "\n".join(blocks)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out-dir", default=str(ROOT / "shorts" / "captions"))
    parser.add_argument("--tolerance", type=float, default=0.6,
                        help="max |cumulative - rendered aiff| seconds before flagging")
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    failures = []
    for episode in sorted(CLIPS):
        for language, narration in TEXT[episode].items():
            voice, rate = VOICE[language]
            sentences = split_sentences(narration)

            ends = []
            for i in range(1, len(sentences) + 1):
                prefix = " ".join(sentences[:i])
                ends.append(spoken_duration(prefix, voice, rate))

            rendered = MASTERS / f"ep{episode:02d}-{language}.aiff"
            drift = None
            if rendered.exists():
                out = subprocess.run(
                    ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                     "-of", "csv=p=0", str(rendered)],
                    check=True, capture_output=True, text=True)
                drift = ends[-1] - float(out.stdout.strip())

            srt_path = out_dir / f"shiropico-ep{episode:02d}-{language}.srt"
            srt_path.write_text(build_srt(sentences, ends), encoding="utf-8")

            flag = ""
            if drift is not None and abs(drift) > args.tolerance:
                flag = "  <-- DRIFT over tolerance"
                failures.append((srt_path.name, drift))
            print(f"{srt_path.name}: {len(sentences)} cues, ends {ends[-1]:.2f}s"
                  + (f", drift {drift:+.2f}s" if drift is not None else "")
                  + flag)

    if failures:
        print("\nTiming did not match the rendered narration for:", file=sys.stderr)
        for name, drift in failures:
            print(f"  {name}: {drift:+.2f}s", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
