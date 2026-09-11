# Producing an episode

`bin/produce.cljk` is the command `kotoba-lang/loop-ka-production` invokes for
the `shiropico` channel. The split is the loop's, not ours:

| owns | where |
|---|---|
| cadence, admission, verdict, evidence | `loop-ka-production` |
| resident execution (placement, slots, retry) | `kotoba-lang/murakumo` task plane |
| **producing one episode, and reporting what actually ran** | **this repo** |

```bash
CP=src:../../kotoba-lang/comfyui/src
nbb --classpath $CP bin/produce.cljk episode-11              # render what is missing
nbb --classpath $CP bin/produce.cljk episode-12 --lang zh
nbb --classpath $CP bin/produce.cljk episode-11 --dry-run    # report without rendering
nbb --classpath $CP bin/produce.cljk episode-11 --limit 2    # bound a run
```

`kotoba-lang/comfyui` supplies `comfyui.native` / `comfyui.native-client` — the
node-graph builder and the client for ComfyUI's own protocol. It is on the
classpath rather than copied here, because ghosthacker needs the same thing and
a copy in two content repos would diverge.

It prints one EDN map:

```clojure
{:plan/id "episode-11" :lang :en
 :shotlist "episode-11-shotlist-v2.en.edn"
 :shots 23 :lines 61
 :legs {:video [...] :voice [...] :bed false :sfx [...] :overlays 14}}
```

## The catalog

`production-catalog/*.edn`, one file per episode. The loop lists that directory,
takes the `.edn` basenames in sorted order as the running order, and passes the
next unconsumed id back as the argument.

One file per **episode**, with the ten languages inside — not one per
episode+language. Ten catalog ids per episode would make the running order ten
nights long for one episode.

Only episodes with a shotlist are in the catalog. Episodes 1–10 have scripts
(`episodes/episode-NN.<lang>.md`) but no shotlist, so they are not listed: a
catalog entry the producer cannot render is worse than an absent one, because
the loop would admit it and then grade the result.

> `catalog/` (singular, existing) holds `ai-gftd-datasets.edn` and is unrelated.
> Pointing the channel at it would have listed zero `.edn` files and read as an
> exhausted catalog — the same trap the yukkuri channel hit with `content/`.

## Legs are a report, not an intention

`:legs` names **what actually served each leg**, derived from render results —
not from whether an env var is set. That earlier version made a claim about
configuration, and reported a served leg for a URL nothing was listening on.

| scene | video leg |
|---|---|
| this run rendered it | `:comfy` |
| failed, or never attempted | `:placeholder` |

`:video` indexes scenes and `:voice` indexes dialogue lines, so the two vectors
have different lengths (episode 11: 23 and 61). `loop-ka.evaluate` treats them
as independent, so that is correct — but `silent-shots` for this channel returns
**line** indices.

### Voice

shiropico is a video channel, so unlike a manga it genuinely **has** a voice leg
per dialogue line — and as of 2026-07-31 there is a speech backend, so the leg
is a result rather than a standing `:silent`.

```
TTS_URL=http://100.82.98.110:8190     # kokoro-http on the fleet head node
```

| line | voice leg |
|---|---|
| this run spoke it | `:kokoro` |
| failed, no text, or no backend | `:silent` |

Speaker → voice is in `shiropico-produce.tts/voices`: SHIRO and PICO are the two
leads and get their own; anything else (narration, incidental) gets the
narration voice rather than silently borrowing a lead's.

Files land in `production-out/<plan>/<lang>/voice/<n>-<speaker>.wav`.

## The image backend is ComfyUI, spoken natively

```
COMFY_URL=http://100.82.98.110:8188   # murakumo fleet head node `gad`, over Tailscale
```

`MURAKUMO_BACKEND_URL` is accepted as a fallback name.

**murakumo.cloud does not serve images** — that Worker proxies
`/api/v1/chat/completions`, `/responses` and `/messages`, text only.

Rendering is sequential (one ComfyUI, one GPU) and `--limit N` bounds a run.
PNGs land in `production-out/<plan-id>/<lang>/`, named by `scene_key`, and are
gitignored: large binaries stay out of git history and a run is reproducible
from the shotlist plus the seed.

### Prompts are re-segmented into tags

These shotlists carry prose. `shiropico-produce.prompt` splits on commas (the
prose is already comma-delimited fragments) and cuts a long fragment once at the
preposition separating subject from setting, so the subject stops sitting
mid-clause where SDXL loses it. Nothing is invented and nothing is dropped — a
test asserts every emitted tag appears in the source prose.

### The scene checkpoint is `animagine-xl-4.0`, chosen by measurement

Tagging alone did **not** fix the shot. The same prompt was then rendered
through all four checkpoints the server has (2026-07-31,
`geothermal_plant_dawn`):

| checkpoint | result |
|---|---|
| `Illustrious-XL-v2.0` (library default) | landscape and neon lines, **no plant** |
| **`animagine-xl-4.0`** | **pipework, plant structure, teal data lines** |
| `noobai-XL-1.1` | atmospheric abstract towers, not a plant |
| `waiREALCN_v150` | photoreal single tower — wrong style for the series |

Illustrious is a **character** model, which is why it kept dropping industrial
subjects; shiropico's shots are establishing shots. `scene-config` in
`bin/produce.cljk` overrides the library default for this channel.

ghosthacker deliberately keeps Illustrious: its panels are character-heavy manga
and 255 of arc0-1's 257 are already drawn with that look, so switching would
make one episode inconsistent with itself.

Still not perfect — the re-render came out at a low angle rather than the aerial
wide the prompt asks for. Framing is a further craft question and not this
loop's to settle.

## Tests

```bash
nbb --classpath src:test test/shiropico_produce/produce_test.cljk
```

Each case pins one bug the first working version shipped with, none of which
were visible without running it: `%s` substitution putting the episode number in
the language hole; counting all 84 rows as shots when 23 are scenes; indexing
voice legs by scene rather than by dialogue line.

## Relationship to `clj/`

`clj/` is the SHIRO & PICO **publish** actor (advisor ⊣ PolicyGovernor ⊣ ledger,
`shiropico.render` on comfyui gateway). It decides whether a cut may be
published. This producer does not call it and holds no publishing key — the loop
decides whether to *ask*, and publishing stays with the actor.
