# SHIRO & PICO automation rules

## YouTube credentials

- Use the OAuth desktop-client JSON stored in 1Password vault `gftdcojp` as
  `SHIRO PICO YouTube OAuth Desktop Client` (item ID
  `mrisveluwnqtoeezu5xxog3nnm`).
- Retrieve it only when needed with `op document get`; write it to a
  permission-0600 temporary file created with `mktemp`, and remove that
  temporary file after the command finishes.
- Never commit OAuth client JSON, access tokens, refresh tokens, API keys, or
  rendered environment files. Never print their values in logs or agent
  responses.
- Store the channel refresh token in the same `gftdcojp` vault in the separate
  item `SHIRO PICO YouTube OAuth Token` (item ID
  `6cijhv5xxeh67enipv62iax2ay`). Do not reuse credentials from another YouTube
  series or channel.
- Before uploading, call `channels.list?mine=true` and verify the returned
  channel identity. Upload new renders as `unlisted` unless the user explicitly
  authorizes public publication.
- Use `kotoba-lang/com-youtube` (pinned in `nbb.edn`) for videos, captions and
  thumbnails, through the `tools/youtube-*.cljs` operators. Do not add a second
  client: the channel guard and the privacy read-modify-write live in that
  library precisely so no tool re-derives them.

See `YOUTUBE-PUBLISHING.md` for the operator workflow.
