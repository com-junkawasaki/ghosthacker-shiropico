# SHIRO & PICO — binary assets → DataLad / B2

**This repo is itself a DataLad dataset** (`datalad create -c text2git`, git-annex
MD5E). Binary content is annexed and stored on the shared `gftdcojp-m365-annex`
B2 bucket; git carries only annex pointers. Text (`.edn`/`.md`/`.json`) stays in
git normally — that is what the `text2git` config buys us. Pattern:
ADR-2605302300 (DataLad + git-annex + IPFS pin) + ADR-2605312345 (B2 backend),
as applied to the `gftd-*-actor` datasets.

Nothing binary is git-ignored anymore. Drop a file in `shorts/renders/`,
`shorts/masters/`, `character-refs/`, `resources/` and `datalad save` routes it
to annex automatically via `.gitattributes`.

| | |
|---|---|
| Dataset | this repo (`orgs/gftdcojp/ai-gftd-ghosthacker-shiropico`) |
| Annex backend | MD5E, `text2git` (binary → annex, text → git) |
| Special remote | `b2` — S3-compatible, `signature=v4`, `chunk=50MiB`, `encryption=none` |
| Bucket | `gftdcojp-m365-annex`, `fileprefix=ai-gftd-ghosthacker-shiropico/` |
| Endpoint | `s3.us-west-004.backblazeb2.com` (region us-west-004) |
| Creds | resolved by `scripts/b2-creds.cljs` (env → 1Password → Keychain); never stored in-repo |
| Contents | ep02–05: 16 Seedance renders (mp4+png), 12 localized masters (en/hi/ar), 12 narration aiff |

## Everyday use

```bash
ROOT=<superproject root>
eval "$(cd "$ROOT" && nbb --classpath "$ROOT:$ROOT/scripts/nbb_compat:$ROOT/orgs/kotoba-lang/secret-resolve/src" \
  "$ROOT/scripts/b2-creds.cljs")"

datalad save -m "add ..."     # annex + commit (content still local only)
datalad push --to b2          # upload content to B2
datalad drop <path>           # free local bytes (recoverable from B2)
datalad get  <path>           # restore from B2
```

From the superproject, the west-integrated equivalents are
`nbb manifest/west_annex.cljs annex-get` / `annex-drop`.

## History / recovery leads for ep01

An earlier attempt used a **separate, external** dataset at
`~/gftdcojp/ghosthacker-shiropico-assets` pushing to a *different* bucket
(`s3://ai-gftd-datasets/ghosthacker-shiropico/{panels,motion-comic}/`) with
bucket-scoped creds in Keychain `gftd.b2` (`DATASETS_KEY_ID` /
`DATASETS_APPLICATION_KEY`), plus IPFS pins. **That directory no longer exists on
the current machine.** A full recovery sweep was run on 2026-07-25; results below
(ADR-2607252000 ledger seq 63).

### ✅ Character refs are NOT lost

The shiro/pico gen2 refs + 12-emotion sets are alive and **git-tracked** in
`orgs/gftdcojp/ai-gftd-yukkuri` at
`appview/ai-gftd-wasm-yukkuri-y5kk5r1x/svelte/static/img/` — 43 `kamishibai-*`
files plus `emotions/{shiro,pico}` (24 files = 12 emotions × 2 characters).
No annex or B2 dependency. Use these as the reference source.

### ✅ ep01 was NOT lost — recovered 2026-07-25

An earlier pass in this file claimed the 5 panels and the 26s motion comic were
unrecoverable. **That was wrong.** Everything is intact in B2 bucket
`ai-gftd-datasets` under `ghosthacker-shiropico/` — 297 files, 9.9 GB:

| Path | Contents |
|---|---|
| `panels/` | all 5 (`sp-ep01-p01-coldopen` … `p05-ghost`, 832×1216 PNG) |
| `motion-comic/` | `sp-ep01-motion-comic.mp4` (8.60 MB, measured 26.000 s) + `-v2.mp4` |
| `episode/` | **82 files, 9.58 GB** — ep01–ep07 full episodes in 11 languages (ar bn de en es fr hi ja pt ta zh), ep08–ep12 in ja |
| `ep-scenes/` `bgm/` `opening/` `op-cuts/` `thumbnails/` `ads/` `coscientist/` | 178 stills, 8 BGM tracks, OP cuts, etc. |

The panels and motion comic are now recovered into `ep01/` in this repo,
annexed, and pushed to `gftdcojp-m365-annex`, so they are covered by
`scripts/annex-custody-verify.cljs`.

**Why the earlier pass got it wrong** (worth knowing, it will happen again):
the sweep tried the current annex key (bucket-scoped → `not entitled`) and
Keychain `gftd.b2` `DATASETS_KEY_ID`/`DATASETS_APPLICATION_KEY` (absent), then
concluded no surviving key could reach the bucket. It never consulted the
`secrets-location-map` skill, which documents an **account-wide Backblaze Master
Application Key** — 1Password item `BACKBLAZE 260225 application keys`, fields
`260421-BACKBLAZE_MASTER_KEY_ID` / `260421-BACKBLAZE_MASTER_KEY`. With that key
the bucket opens normally. **When a credential seems missing, read
secrets-location-map before concluding the data is gone.**

The IPFS finding stands: both CIDs return **504** on ipfs.io / dweb.link /
w3s.link / 4everland.io and are absent locally, so those pins really did lapse
(`bafybeib7ylao5c6bw45flkuwrnjrz4xgb7idpvo3qqzwvst6uqnclgdq7y`,
`bafybeigoeuzoisczxqozbnz2lvsnbp6tagyf342yvmmqpwrpvj6hzdv2yy`). One dead
backup route is not a dead asset — that conflation was the actual mistake.

### ⚠️ The 9.58 GB episode catalog has no custody

`episode/` (ep01–07 × 11 languages, ep08–12 ja) belongs to **no git repo and no
DataLad dataset**, so `annex-custody-verify` does not see it, and its bucket
opens only with the account master key. This is the same exposure that nearly
lost ep01, at ~70× the size. Needs a custody decision.

Regeneration is still possible if ever needed (inputs
`episode-01-shotlist{,-v2}.json` in 11 languages + `episode-01.md` +
`SERIES-BIBLE.md`, generators `comfy/scripts/shiropico-*.py`), but it is no
longer the only option.

That older note also claimed *"git-annex S3 special-remote hangs on B2 'checking
bucket'"* and worked around it with direct boto3 uploads. **That no longer
reproduces** — with git-annex 10.20251215, `initremote` against
`gftdcojp-m365-annex` returned `(checking bucket...) ok`, all 40 files copied,
and a `drop` → `get --from b2` round-trip returned byte-identical content. Use
the annex path; the boto3 workaround is retired.

## Provenance

Character refs sourced from the live Shiro/Pico cast at
`60-apps/ai-gftd-project-yukkuri/.../svelte/static/img/{kamishibai-shiro-gen2*,kamishibai-pico*,emotions/{shiro,pico}}`.
Design recovered from session `be34e38d` (see `SERIES-BIBLE.md`).
