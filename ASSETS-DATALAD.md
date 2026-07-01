# SHIRO & PICO — binary assets → DataLad / B2

Character reference images and generated panels / motion-comic binaries are **not in git** —
they live in a DataLad dataset (keep the main repo pack slim). Pattern: ADR-2605302300
(DataLad + git-annex SHA256E + IPFS pin) + ADR-2605312345 (B2 backend).

| | |
|---|---|
| Dataset | `~/gftdcojp/ghosthacker-shiropico-assets` (datalad, git-annex SHA256E) |
| B2 location | `s3://ai-gftd-datasets/ghosthacker-shiropico/{panels,motion-comic}/` (direct S3 upload via boto3) |
| Note | git-annex S3 special-remote hangs on B2 "checking bucket"; uploaded via boto3 S3 (signature v4, path-style) instead. DataLad still versions provenance locally. |
| Endpoint | `https://s3.us-west-004.backblazeb2.com` (region us-west-004, signature v4, path-style) |
| Creds | macOS Keychain `gftd.b2` / `DATASETS_KEY_ID` + `DATASETS_APPLICATION_KEY` (bucket-scoped) |
| IPFS top CID | `bafybeib7ylao5c6bw45flkuwrnjrz4xgb7idpvo3qqzwvst6uqnclgdq7y` (panels + motion-comic, CIDv1) |
| Motion-comic CID | `bafybeigoeuzoisczxqozbnz2lvsnbp6tagyf342yvmmqpwrpvj6hzdv2yy` (sp-ep01-motion-comic.mp4) |
| Contents (ep01) | 5 panels, 26s motion comic (1080×1920), character-refs (shiro/pico gen2 + 12-emotion sets) |

## git-ignored locally (route to DataLad, not git)
- `data/ghosthacker-shiropico/character-refs/`
- `data/ghosthacker-shiropico/resources/`

## Retrieve
```bash
export AWS_ACCESS_KEY_ID=$(security find-generic-password -s gftd.b2 -a DATASETS_KEY_ID -w)
export AWS_SECRET_ACCESS_KEY=$(security find-generic-password -s gftd.b2 -a DATASETS_APPLICATION_KEY -w)
datalad get -d ~/gftdcojp/ghosthacker-shiropico-assets .        # git-annex → B2
ipfs get bafybeib7ylao5c6bw45flkuwrnjrz4xgb7idpvo3qqzwvst6uqnclgdq7y   # IPFS pin
```

## Provenance
Character refs sourced from the live Shiro/Pico cast at
`60-apps/ai-gftd-project-yukkuri/.../svelte/static/img/{kamishibai-shiro-gen2*,kamishibai-pico*,emotions/{shiro,pico}}`.
Design recovered from session `be34e38d` (see `SERIES-BIBLE.md`).
