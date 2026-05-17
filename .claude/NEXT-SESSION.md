# Task: action REVIEW.md

This codebase is Golova, a ClojureScript Datalog-backed PKM. There's a recent architectural review at `REVIEW.md` at the repo root — it's your work plan for this session. Before touching anything:

## Read the orientation skill first

`.claude/skills/golova-orientation/SKILL.md` is the project map (where each file lives, the rebuild loop, common operations, things-that-look-broken-but-aren't). Read it before opening any source file. Three sibling skills auto-surface as you work: `datahike-cljs-gotchas`, `reagent-form2-prop-staleness`, `browser-smoke-test`. Skim their descriptions so you know they exist.

## Clone Datahike for reference

Several REVIEW items cite specific Datahike internals (`storeless-config` in `config.cljc` line 143, schema validation predicates in `schema.cljc`, the experimental compiled query engine). Reading those source files is faster than reverse-engineering. Clone:

```bash
cd /tmp && git clone --depth 1 https://github.com/replikativ/datahike.git
```

The jar at `~/.m2/repository/org/replikativ/datahike/0.8.1681/datahike-0.8.1681.jar` has the same `.cljc` source if you prefer offline, but the GitHub repo has docs (`query-engine.md`, `time-travel.md` etc.) that aren't packaged.

## Dev server

Port **8088**, not 8089 (README is stale).

```bash
npx shadow-cljs watch app
```

Wait for `Build completed` before testing. Use the `browser-smoke-test` skill to verify UI changes in headless Chrome — compile-clean ≠ feature-works. There's a working puppeteer harness at `/tmp/puppet-test/` from the last session; it injects the example EDN via localStorage to bypass the file-picker.

## Context the agent doesn't otherwise have

- **Naga was removed** in commit `8e580da`; the rule engine is now a hand-rolled materialiser (`materialize-rules` in `state.cljs`). The README still says "Naga for the rules engine" — that's a known doc bug REVIEW flags.
- **`proof.cljs` doesn't exist** — also flagged in REVIEW, also a doc bug.
- **The user has live data in localStorage** under key `golova.state.v1`. Any change to schema enforcement or persistence shape risks corrupting it. Either prove the change is backwards-compatible OR talk to the user about Settings → Reset before shipping.
- **`examples/personal-kb.edn`** is the canonical demo data — 3 domains (people, books, tasks), exercises every rule shape (transitive, joins, arity-1 boolean flags). Use it as the smoke-test corpus.
- The user is fine with you pushing back on REVIEW items that aren't worth it. The review is opinionated; some findings are pedantically correct but low-value.

## Execution order

REVIEW is well-prioritised but staged like this to manage blast radius:

**Phase 1 — Doc/cosmetic (no risk, do without asking):**
- Delete `proof.cljs` from README's "What's where" block.
- Remove the "Naga for the rules engine" line from README, update to "Hand-rolled fixed-point materialiser on Datahike."
- Fix README's "Run" section to say port 8088, not 8089.
- The `type-of-value` O(n²) scan in `ui.cljs` (Performance note): convert to a memoised `constructor→type` lookup map built once per render. ~10 lines, real win on big tables.

**Phase 2 — The headline finding (Critical #1, schema enforcement):**
`d/empty-db` defaults to `:schema-flexibility :read`, so all the schema metadata is dead code. The one-line fix is `(d/empty-db schema {:schema-flexibility :write})`.

This is the most impactful single change in REVIEW but it WILL reject inputs that the schema-on-read mode quietly accepted. Before touching it:
1. Ask the user how to handle migration of their saved state. Two reasonable paths: (a) ship + tell them to Reset, (b) gate it behind a settings toggle while their data is grandfathered.
2. Run `examples/personal-kb.edn` through the change locally first. Some of its data might fail the stricter validation (e.g. multi-valued attrs that were never declared `:many`).

**Phase 3 — Performance (Critical #3, full-rebuild-on-every-mutation):**
Real fix is moving to `d/connect` + incremental `d/transact`. This is a substantial refactor — touches every `append-event!` caller and changes the persistence story. Don't do this until the user complains about latency or you've shipped Phase 2 and have stable footing.

**Phase 4 — Storage (Critical #4, localStorage limits):**
Konserve + IndexedDB. Real engineering. Defer until the user actually hits the ~5MB limit OR explicitly asks for it.

**Phase 5 — Structural (Design Tension #1, monolithic ui.cljs):**
Splitting into `views/*.cljs`. Big, no user-visible change. Don't do this preemptively — wait until the monolith is actively friction.

## Things REVIEW gets wrong / overstates

- **Critical #7 ("query parser is a security/injection vector"):** This is a client-side app. The user IS the only attacker. Worst case they crash their own tab. Don't spend cycles "hardening" — just make sure errors surface clearly in the results pane (they already do).
- **Performance #2 ("extend-type! triggers rebuild per add"):** The `typed-input` "+ new" inline path commits immediately on purpose so the dropdown re-renders with the new option. Batching would require deferring + a separate save step, which would surprise users. Ask before changing.
- **"No tests":** True, but for a single-user local-first app where the data model is event-sourced and easily re-importable, the cost/benefit is genuinely unclear. Don't add a test framework unless you're shipping something risky enough to need it.

## Smoke-test checklist after Phase 2

If you ship the `:schema-flexibility :write` change, verify in headless Chrome:

1. Fresh load: import `examples/personal-kb.edn` via Settings → Import — should succeed with no console errors.
2. People domain, Rules view: should show ~172 stored facts (count visible in "Stored facts" header).
3. Run `dave's ancestors` saved query — expect 4 solutions (`zeke, zoe, alice, bob`).
4. Click into `parent/2` predicate page. Use the add-row form (`+ Add`) to assert a new parent fact. Verify rebuild succeeds and the fact appears.
5. Try the same on `favorite-author/2` — that's the materialised arity-1 path, most likely to break under strict validation.

If any break, Datahike is rejecting something the schema-on-read mode used to swallow. Check the console for the exact validation error and either fix the schema generation (probably `predicate-schema` in `state.cljs`) or migrate the example data.

## When to ask

- Before Phase 2 (schema enforcement) — talk through the migration path.
- Before any task that would touch persistence shape (anything modifying `serializable` / `load-or-seed!` / `import-snapshot!`).
- If a REVIEW item seems wrong or low-value, push back instead of executing.

When unsure: ship the small wins (Phase 1), then come back for direction.
