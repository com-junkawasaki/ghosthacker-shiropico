# Producing an episode

`bin/produce.cljs` is the command `kotoba-lang/loop-ka-production` invokes for
the `shiropico` channel. The split is the loop's, not ours:

| owns | where |
|---|---|
| cadence, admission, verdict, evidence | `loop-ka-production` |
| resident execution (placement, slots, retry) | `kotoba-lang/murakumo` task plane |
| **producing one episode, and reporting what actually ran** | **this repo** |

```bash
nbb --classpath src bin/produce.cljs episode-11
nbb --classpath src bin/produce.cljs episode-12 --lang zh
```

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

`:legs` names **what actually served each leg**, which is the one thing the loop
cannot find out for itself:

| leg | values | degraded |
|---|---|---|
| `:video` (per scene) | `:murakumo` `:comfy` `:placeholder` | `:placeholder` |
| `:voice` (per dialogue line) | `:murakumo` `:local` `:silent` | `:silent` |

With no backend configured every leg is degraded, the loop grades the run
`:degraded`, and it holds instead of publishing. That is the correct outcome —
`loop-ka-production` exists because two weeks of flat pastel cards shipped
nightly while nothing asked whether the generative legs had run.

`:video` indexes scenes and `:voice` indexes dialogue lines, so the two vectors
have different lengths (episode 11: 23 and 61). `loop-ka.evaluate`'s
`silent-shots` therefore returns **line** indices for this channel.

`:bed` is always `false`: these shotlists carry per-shot `sfx` and `fx` but no
music bed, so a run grades `:thin` at best. Claiming a bed to reach `:clean`
would be the same lie in a different leg.

## Backends

Read from the environment; presence of a URL is taken as reachability.

| var | serves |
|---|---|
| `MURAKUMO_BACKEND_URL` | images and voice |
| `COMFY_URL` | images |
| `VOICE_LOCAL_CMD` | voice |

That is an assumption and the weakest link here: an unreachable URL is reported
as a served leg. On the murakumo task plane the node's own `:requires` gate is
what establishes the capability. Replacing this with a real probe is the obvious
next step.

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
