# ai-gftd-ghosthacker-shiropico/clj — shiropico publish actor

SHIRO & PICO cut publish actor. See `../../../../CLAUDE.md` "Actors" section
for the pattern this follows (containment + independent governor +
append-only ledger). Ported from `com-etzhayyim-tsumugu` (Spirit in
Physics' publish actor), adapted to animeka's `episode → cut` domain.
Decision record:
`../../../../90-docs/adr/2607012000-shiropico-publish-actor.md`.

## Invariant

`shiropico.advisor` (the containment node) never proposes a commit for a
cut/episode that doesn't exist. The PolicyGovernor never lets a stub
(non-gateway) render or a `:high-stakes?` cut auto-commit without a human.
Only `:commit` writes the Store; every commit/hold is an append-only ledger
fact.

## Conventions

- `.kotoba` is the authority for newly migrated application decisions. The
  first slice is `src/shiropico/publish_decision.kotoba`; the existing
  `.cljc` policy/phase implementation remains a compatibility oracle until
  the operation host invokes the compiled artifact in production.
- `.cljc` for portable compatibility code (store/phase/policy/operation/
  advisor/render) — `.clj` only for JVM-only I/O (cacao, kotoba).
- `shiropico.render` builds directly on `kotoba-lang/genapp-clj` (own
  `MichibikiKSampler`-equivalent config, `ShiropicoKSampler`) — it does
  **not** depend on `ai-gftd-animeka` itself (an actor shouldn't depend on
  another app's deploy artifact); it's a third, independent genapp-clj
  consumer alongside mangaka/animeka.
- `shiropico.cacao`/`shiropico.kotoba` are faithful ports of
  `itonami.cacao`/`itonami.kotoba` (via `tsumugu.cacao`/`tsumugu.kotoba`) —
  kept in sync by hand (documented in each namespace's docstring), not
  shared as a runtime dependency, so this actor's own dependency footprint
  stays self-contained (containment applies to deps, not just the
  intelligence node).
- `clojure -M:lint` (clj-kondo, errors fail) / `clojure -M:dev:test`.
