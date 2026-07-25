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
the current machine**, and its contents — ep01's 5 panels, the 26s motion comic
(1080×1920), and the shiro/pico gen2 character refs + 12-emotion sets — are *not*
in this dataset. They are the reason `tools/youtube_publish_short.py` (ep01)
currently has no media to run against. Recovery leads, unverified:

- IPFS top CID (panels + motion comic): `bafybeib7ylao5c6bw45flkuwrnjrz4xgb7idpvo3qqzwvst6uqnclgdq7y`
- Motion-comic CID (`sp-ep01-motion-comic.mp4`): `bafybeigoeuzoisczxqozbnz2lvsnbp6tagyf342yvmmqpwrpvj6hzdv2yy`
- B2 bucket `ai-gftd-datasets` under `ghosthacker-shiropico/`

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
