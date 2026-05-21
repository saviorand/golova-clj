---
name: golova-orientation
description: Project map for Golova — what each file does, the data model, the rebuild loop, where to make common kinds of changes. Read first when picking up unfamiliar work on this codebase.
---

# Golova orientation

## What it is

Client-side ClojureScript PKM (personal knowledge management) over Datahike.
Notion-flavored UI, atoms-and-triples data model. ~3000 lines across four
files. No server, distributes as static files, persists to localStorage.

See `REVIEW.md` at the repo root for the architectural take. This skill is
the *operational* map — what to grep for when adding a feature.

## File map

- `src/golova/state.cljs` — single source of truth. `app-state` r/atom holds
  every domain plus UI state (selection, modal, popover, palette, home).
  Per-domain: `:db` (immutable Datahike value), `:db-schema`, `:rules`,
  `:events` (assert/retract log), `:imports`, `:schema` (UI metadata: types,
  predicates, queries).
- `src/golova/ui.cljs` — every Reagent view. Top-down: helpers
  (atom-link, pred-link, chip-editor, markdown, table-toolbar, type-value),
  then sidebar, topbar, rules-view, predicate-view, type-view, rule-view,
  query-view, entity-view, home-view, modal, popover, palette, root.
- `src/golova/storage.cljs` — LocalStorage backend + EDN snapshot
  export/import. Tiny.
- `src/golova/app.cljs` — entry point: hydrate state, sync theme, bind
  ⌘K/⌘↵/Esc shortcuts, mount Reagent root.

## Build & dev

```bash
npx shadow-cljs watch app          # serves on port 8088 (README says 8089, it's wrong)
```

Wait for `Build completed` after edits. Use the `browser-smoke-test` skill
for actual UI verification — `Build completed` only proves no syntax errors.

## Probing the live app (cljs REPL from bash)

When the watch is running, you can evaluate ClojureScript in the connected
browser runtime without spinning up nREPL/Calva — useful for inspecting
`app-state`, running ad-hoc queries against the live `:db`, or sanity-
checking a function before editing more code. Much faster than
`println` + recompile + refresh.

```bash
npx shadow-cljs clj-eval \
  '(shadow.cljs.devtools.api/cljs-eval :app "(require (quote [golova.state :as s])) (keys @s/app-state)" {})'
```

Returns `{:results ["..."] :out "" :err "..." :ns cljs.user}`. The form's
return value is the *string* inside `:results`. `:err` is noisy (datahike
`:redef` warnings on every require) but harmless when `:results` is
non-empty — read past it.

Quoting note: the outer shell single-quotes the clj form; inside, use `\"`
or the `(quote ...)` reader for cljs-side quoting. A browser tab must be
connected to the watch (check shadow's dashboard at localhost:9630).

## The rebuild loop

This is the central trick. After ANY state-changing operation
(assert/retract, declare-type!, set-rules!, etc.), `rebuild!` runs:

1. For each domain, replays `:events` into a fresh `d/empty-db` with the
   computed schema.
2. Runs `materialize-rules` to fixed-point — derived facts become real
   datoms in the store (sidesteps a Datahike CLJS rules bug; see
   `datahike-cljs-gotchas`).
3. Replaces `:db` and `:db-schema` on the domain.

So queries always read from a freshly-materialized db. There is no caching;
correctness comes from determinism.

`save!` then writes `serializable` to localStorage. The persisted blob is
small: rules + events + schema + imports — the `:db` is recomputed on load.

## Data model conventions

- **Atoms** are entities with `:db/ident`. Refer to them by their bare
  keyword in any ref position or query.
- **Predicates** (Datahike attrs) are auto-installed from `schema.predicates`
  — ref types for atom-valued, scalars for int/string.
- **Rules** live as Datalog clauses: `[(head ?a ?b) body…]`. Rule heads
  also get schema entries so their materialised outputs can be transacted.
- **Notes** are a special `:note` attribute (string, cardinality :one) on
  any entity. Wikilinks `[[name]]` in note markdown become atom-links at
  render time; `note-backlinks` finds every note that points at a given
  entity.

## Common operations and where they live

| To… | Look at… |
| --- | --- |
| Add a new selection kind (a new "page") | `main` dispatch in ui.cljs + a new view fn + `select!` callers |
| Wire a new command palette entry | `palette-candidates` in ui.cljs |
| Add a new modal | `modal` fn in ui.cljs (`case (:kind m)`) |
| Change persisted state shape | `serializable` + `load-or-seed!` + `import-snapshot!` in state.cljs |
| Add a new schema feature | `predicate-schema` + `rule-head-attrs` + `build-schema` in state.cljs |
| Change rule semantics | `expand-rule` + `materialize-rules` in state.cljs |

## Patterns to follow

- **Form-2 components**: see the `reagent-form2-prop-staleness` skill.
  Always declare props on the inner fn, always reset local atoms on
  prop change.
- **Forward declarations**: ui.cljs uses `(declare atom-link rule-clause
  sort-indicator cycle-sort)` near the top because the file isn't strictly
  in dependency order. Add to that list if you make new circular calls.
- **Local UI state**: per-view ephemera (input drafts, editing flags,
  filter/sort state for a table) lives in a per-instance r/atom on a
  form-2 component, not in app-state. Persisted UI state (theme, expanded
  domains, current selection, home onboarding collapse) lives in
  app-state and is captured by `serializable`.
- **Don't import naga.** It's gone. Anywhere you see Pabu syntax or
  `:rdf/type` arity-1 tricks, that's pre-port code.

## Example data

`examples/personal-kb.edn` is the canonical demo. Three domains (people,
books, tasks) covering every rule shape (transitive, joins, arity-1).
Import via Settings → Import, or by injecting into localStorage in a
puppeteer test.

## Things that look broken but aren't

- The dev server port is **8088** (the README says 8089 — it's stale).
- `[?a :parent ?b]` means "a is parent of b", not the inverse. Naming
  matters when writing rules; see `datahike-cljs-gotchas`.
- Discovered predicates can show counts like `favorite-author/2 5` when
  the rule is actually arity-1 — the materialisation stores `true` as the
  value so it's arity-2 in the store. The predicate page's "Defined by"
  section is what reveals the rule.

## When to ship

Test in browser via `browser-smoke-test`. If the test renders without
errors and the screenshot looks right, ship. If you can't get to a
browser test, say so explicitly — don't claim correctness from
`Build completed` alone.
