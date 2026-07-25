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

### ❌ ep01 panels + motion comic are gone

The 5 panels and the 26s motion comic (1080×1920) were not recovered. Every lead
was tried and failed:

| Lead | Result |
|---|---|
| IPFS local pin / block | not pinned; block absent offline |
| IPFS network (daemon up, peers connected, 120s) | did not resolve |
| Gateways ipfs.io / dweb.link / w3s.link / 4everland.io (both CIDs, redirects followed) | **HTTP 504** on all |
| B2 bucket `ai-gftd-datasets` | current key is bucket-scoped → `not entitled` (even ListBuckets) |
| Keychain `gftd.b2` `DATASETS_KEY_ID` / `DATASETS_APPLICATION_KEY` | not found |
| `data/ghosthacker-shiropico/` in `ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-mangaka` | exists, but **text only** (1.8M); no `character-refs/`, no `resources/`, zero png/mp4/jpg/wav |

Dead CIDs, for the record:
`bafybeib7ylao5c6bw45flkuwrnjrz4xgb7idpvo3qqzwvst6uqnclgdq7y` (top),
`bafybeigoeuzoisczxqozbnz2lvsnbp6tagyf342yvmmqpwrpvj6hzdv2yy` (motion comic).

### 🔄 ep01 is regenerable, though

Both the inputs and the generators survive, so ep01 can be rebuilt (it will not
be byte-identical to the original art):

- Inputs: `episode-01-shotlist{,-v2}.json` in 11 languages, `episode-01.md`,
  `SERIES-BIBLE.md`, `character-design-spec.json`
- Generators: `comfy/scripts/shiropico-ep01-gen.py`,
  `shiropico-motion-comic{,-v2}.py`, `shiropico-build-episode{,-v2,-v3}.py`,
  `shiropico-opening{,-v2}.py`, `shiropico-thumbnail.py`

Remaining unexplored lead: a 1Password item holding a key entitled for
`ai-gftd-datasets`. Its identifier is unknown and was **not** guessed at (vault
enumeration is disallowed) — ask the owner for the exact item name.

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
