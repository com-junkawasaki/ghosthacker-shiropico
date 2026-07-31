# Producing an episode

`bin/produce.cljs` is the command `kotoba-lang/loop-ka-production` invokes for
the `shiropico` channel. The split is the loop's, not ours:

| owns | where |
|---|---|
| cadence, admission, verdict, evidence | `loop-ka-production` |
| resident execution (placement, slots, retry) | `kotoba-lang/murakumo` task plane |
| **producing one episode, and reporting what actually ran** | **this repo** |

```bash
CP=src:../../kotoba-lang/comfyui/src
nbb --classpath $CP bin/produce.cljs episode-11              # render what is missing
nbb --classpath $CP bin/produce.cljs episode-12 --lang zh
nbb --classpath $CP bin/produce.cljs episode-11 --dry-run    # report without rendering
nbb --classpath $CP bin/produce.cljs episode-11 --limit 2    # bound a run
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

> `catalog/` (singular, existing) holds `ai-gftd-datasets.json` and is unrelated.
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

### Voice is `:silent`, and that is not a configuration problem

shiropico is a video channel, so unlike a manga it genuinely **has** a voice leg
per dialogue line. There is simply no TTS wired: murakumo's `:tts` backend is
`:via :proc` (CosyVoice2/Kokoro), not an HTTP endpoint, and nothing answered on
the fleet head node. So every voice leg is `:silent`, the loop grades the run
`:degraded`, and it holds.

That should stay visible. Leaving `:voice` empty would grade the run `:thin` and
hide a missing half of the pipeline behind a passing verdict. Empty is right for
a manga, which has no voice leg at all; it is wrong here.

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

### Known: prose prompts under-perform here

These shotlists carry **prose** `scene_prompt`s ("Aerial wide shot of a massive
geothermal power plant on a black lava plateau at near-arctic dawn, ..."). The
first render produced the plateau, the dawn and the teal data lines but **no
power plant** — the subject was dropped.

SDXL checkpoints respond to tag lists, which is the form ghosthacker's panels
use. Converting these prompts to tags, or choosing a checkpoint that handles
prose, is an open craft question. It is not this loop's to settle
(`loop-*` `:must-not :own-domain-scoring-truth`) — recorded so nobody reads
"it produced a PNG" as "it produced the right shot".

## Tests

```bash
nbb --classpath src:test test/shiropico_produce/produce_test.cljs
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
