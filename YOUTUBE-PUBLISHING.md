# SHIRO & PICO YouTube publishing

The YouTube Data API is enabled in Google Cloud project `yukkuri-498208`.
Authentication uses the official Google OAuth 2.0 installed-app flow with the
minimum scopes required to upload and verify the destination channel:

`https://www.googleapis.com/auth/youtube.upload`

`https://www.googleapis.com/auth/youtube.readonly`

`https://www.googleapis.com/auth/youtube.force-ssl`

The desktop OAuth client is stored in 1Password:

- Vault: `gftdcojp`
- Document: `SHIRO PICO YouTube OAuth Desktop Client`
- Item ID: `mrisveluwnqtoeezu5xxog3nnm`

Retrieve it without placing credentials in the repository:

```sh
SHIROPICO_OAUTH_DIR="$(mktemp -d)"
op document get mrisveluwnqtoeezu5xxog3nnm \
  --vault gftdcojp \
  --out-file "$SHIROPICO_OAUTH_DIR/client-secret.json" \
  --file-mode 0600
```

Run the local-browser OAuth consent flow with the project helper:

```sh
nbb tools/youtube-oauth.cljk \
  --client-secret "$SHIROPICO_OAUTH_DIR/client-secret.json" \
  --output "$SHIROPICO_OAUTH_DIR/authorized-user.edn"
```

Save `authorized-user.edn` as a separate 1Password Document named
`SHIRO PICO YouTube OAuth Token` (item ID
`6cijhv5xxeh67enipv62iax2ay`). Delete the temporary directory after
authentication. The helper requests upload, read-only channel verification,
and the force-SSL scope required by YouTube's captions API;
do not replace it with a `gcloud application-default login` invocation that
also grants the unrelated `cloud-platform` scope.

For each release:

1. The tools refuse to write until `youtube.channels/assert-channel!` has
   confirmed the token acts for exactly the SHIRO & PICO channel — you do not
   have to remember to check.
3. Upload the video as `unlisted` with `nbb tools/youtube-publish.cljk`
   (the API client is `kotoba-lang/com-youtube`, pinned in `nbb.edn`).
4. Attach the matching SRT track with `nbb tools/youtube-attach-captions.cljk`.
5. Verify playback, audio, captions, title, description, and Shorts framing.
6. Change visibility with `nbb tools/youtube-set-privacy.cljk --privacy public
   --confirm`, only when explicitly authorized. Without `--confirm` it prints
   what it would do and changes nothing.

Do not use service-account authentication: ordinary YouTube channels require
user OAuth. Do not use VOICEVOX as a publishing dependency; localized audio is
part of the rendered media pipeline.
