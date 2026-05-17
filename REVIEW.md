# Architecture & Stack Review: Golova

### Overview

A ClojureScript single-page app: Notion-flavored PKM (Personal Knowledge
Management) running entirely client-side, backed by Datahike (an in-memory
Datalog database) with localStorage persistence. ~3000 lines across 4 files.

---

### What's Good

1. **Datahike as the core model is a strong choice.** Datalog is an excellent
   fit for a knowledge graph — triples, joins, and recursive rules (like
   `ancestor`) are first-class. The event-sourcing pattern (store raw
   assert/retract events, rebuild the DB on load) is a clean approach that
   gives you a replayable audit log for free.

2. **The domain abstraction is well-designed.** Each domain is a self-contained
   Datahike instance with its own schema, rules, and event log. This scopes
   complexity naturally and the cross-domain import mechanism (stubbed but
   designed) is the right extension point.

3. **Rule materialisation to fixed-point is pragmatic.** Rather than fighting
   Datahike's buggy CLJS rule evaluation (`demand_set.size` error), you rewrite
   rule calls into attribute patterns and materialise outputs. This sidesteps a
   real Datahike CLJS bug while keeping the user-facing syntax natural.

4. **The command palette (⌘K) is well-implemented.** Fuzzy matching over all
   entities, predicates, rules, and commands — this is the kind of UX that
   makes a knowledge tool usable.

5. **PWA-ready static distribution** — no server, no build pipeline beyond
   shadow-cljs. This is a legitimate deployment model for a local-first tool.

6. **Table UX is solid.** The chip-based type editor, provenance filter pills,
   sortable columns, search-within-rows, and type-aware value pills are
   well-constructed. The `table-toolbar` component is properly factored as a
   reusable piece. The `new-type-form` modal is a clean form-2 component with
   disabled-until-valid gating. Auto-suggest of untyped atoms from the store
   bridges the gap between "things that exist" and "things you've declared."

---

### Critical Issues

**1. Single Atom Architecture — the 2000-line `ui.cljs` problem.**

All state lives in one `app-state` atom. `ui.cljs` is **2050 lines** mixing
layout, business logic, formatting, and interaction handlers. There's no
separation between:

- Pure view components
- Stateful interaction logic
- Data transformation

Every view function directly derefs `app-state` and calls `state/*` mutation
functions. This means:

- No component-level testing possible
- Re-render storms: changing `:theme` or `:selection` re-renders the **entire
  tree** because every component derefs the same atom
- The `fn` form-2 pattern is used inconsistently — some components capture
  local state correctly, others deref the global atom in the render function

The new `table-toolbar`, `chip-editor`, `type-value`, `predicate-view`,
`rules-view`, `type-view`, and `new-type-form` were all added directly into
`ui.cljs`. The `table-toolbar` is a good candidate for extraction into its own
namespace — it's a self-contained, reusable component with no coupling to the
rest of the view layer.

**Recommendation:** Split `ui.cljs` into `views/sidebar.cljs`,
`views/predicate.cljs`, `views/entity.cljs`, `views/table-toolbar.cljs`, etc.
Use Reagent cursors or `r/cursor` to scope subscriptions.

**2. `rebuild!` is O(n²) and called on every mutation.**

Every `assert-triple!`, `retract-triple!`, `replace-triple!`, `set-rules!`,
`declare-type!`, etc. calls `rebuild!`, which:

1. Creates a fresh empty Datahike DB
2. Replays the **entire event log** from scratch
3. Runs all rules to fixed-point (up to 25 iterations)
4. Does this for **every domain**, not just the changed one

For a domain with 1000 events and 10 rules, this means 1000 transacts + 250
rule evaluations on every keystroke-save. The `materialize-rules` loop runs
`d/q` for every rule on every iteration, with `distinct` on the full tx set
each time.

The new `extend-type!` function (called from `typed-input`'s "+ new" dropdown)
also triggers `rebuild!` — meaning every time a user adds a constructor via
the inline dropdown, the entire DB is rebuilt and persisted. The chip-editor
batches (rebuild only on "Save changes"), but this path doesn't.

**Recommendation:** Use Datahike's incremental transaction model. Instead of
replaying from scratch, maintain the DB incrementally — transact only new
events, and re-materialise only rules whose dependencies changed.

**3. localStorage serialisation is fragile and unbounded.**

`(pr-str snapshot)` serialises the entire state including all events as EDN.
There's no:

- Size limit or quota checking
- Compression
- Migration strategy (the key is `golova.state.v1` but there's no version
  negotiation)
- Error recovery beyond catching read failures

A domain with 5000 events will produce a multi-MB EDN string. localStorage has
a ~5MB limit per origin in most browsers. You'll hit this silently.

**Recommendation:** Implement incremental persistence (append-only event log
with periodic compaction), add quota detection, and consider IndexedDB for
larger datasets.

**4. The `proof.cljs` file mentioned in README doesn't exist.**

The README references `proof.cljs` as "derivation explainer (proof trees,
ready to surface)" but no such file exists in the source tree. This is
misleading documentation.

**5. No error boundaries or error recovery in the UI.**

If `rebuild!` throws (which it does catch), the error is stored in `:error` but
there's no UI feedback mechanism beyond a top-level error display. If a rule
produces an unexpected schema, the entire app state is corrupted until a page
reload. There's no undo mechanism despite having an event log that could
support it.

The new `pred-add-row` and `entity-add-form` do catch errors locally and
display them inline, which is good. But the core rebuild path is still
unprotected from the user's perspective.

**6. The query parser is a security/injection vector.**

`run-query` calls `reader/read-string` on user input, then passes the result to
`d/q`. While this is a client-side app (so the blast radius is limited to the
user's own data), a malformed query can corrupt the Datahike db or throw
uncaught exceptions that leave the app in a broken state.

---

### Design Tensions

**1. "Types" are a UI fiction, not enforced by the engine.**

Types are declared in `:schema` but never validated against the Datahike store.
You can assert `[:bob :parent 42]` where `:parent` expects `[person person]`
and the engine accepts it. The `coerce-value` function does some parsing but
it's opt-in (only used by the form helper). This creates a confusing mental
model: the UI suggests type safety that doesn't exist.

The chip-editor and `new-type-form` make the type declaration experience
significantly better — but they don't change the underlying tension. Types are
still purely declarative metadata with no enforcement. The `type-value` pill
rendering (color-coded by type) further reinforces the impression that types
are "real" when they're not enforced at the data layer.

**2. Naga is removed but still referenced.**

The README says "Naga for the rules engine" but Naga was explicitly removed in
commit `8e580da`. The actual rule engine is a hand-rolled fixed-point evaluator
in `state.cljs` (the `materialize-rules` function). The README is stale.

**3. Arity-2 hardcoding limits the data model.**

`assert-from-form!` throws if arity ≠ 2. `predicate-schema` only handles arity
1 and 2. The rule materialiser only handles arity 1 and 2. This means you
can't represent ternary relations like `(teaches teacher student subject)` —
you'd need to reify into two binary relations.

---

### Performance Notes from the Table / Type Code

**`type-of-value` does a linear scan per cell.**

```clojure
(defn- type-of-value [domain-id v]
  (when (keyword? v)
    (let [n (name v)]
      (some (fn [t]
              (when (some #{n} (:constructors t))
                (:name t)))
            (get-in @app-state [:domains domain-id :schema :types])))))
```

Called for every cell in every table row. For 200 rows × 10 types × 50
constructors, that's 100,000 string comparisons per render. A precomputed
`constructor→type` lookup map would be O(1) per value.

**`extend-type!` triggers a full rebuild per constructor add.**

The `typed-input` component's "+ new" inline mode calls `extend-type!` which
calls `rebuild!` + `save!`. Adding 5 constructors in a row via the dropdown
means 5 full DB rebuilds. The chip-editor batches (rebuild only on "Save
changes"), which is better — but the `typed-input` path doesn't batch.

**Chip-editor suggestions recompute on every render.**

The `filtered-suggestions` computation runs inside the render function and
calls `suggestions-fn` (which queries the Datahike store) on every render
cycle. Fine for small domains, but could cause jank with large atom sets.

---

### What's Missing

- **No tests.** Zero test files. For a Datalog-backed app with complex rebuild
  logic, this is risky.
- **No TypeScript/externs.** `infer-externs: auto` is set but
  `warnings-as-errors: false` — Google Closure extern inference failures are
  silently ignored, which can cause runtime crashes in advanced compilation.
- **No PWA manifest or service worker.** The README mentions PWA as a goal but
  `index.html` has no manifest link, no service worker registration, and no
  offline capability.
- **No undo.** The event log could support it trivially — every mutation is
  already tagged with an id and timestamp.

---

### Summary

The core idea is sound: Datalog + event-sourcing + static SPA is an excellent
architecture for a local-first PKM tool. The Datahike choice and domain
abstraction are well-motivated.

The latest changes address several concrete UX gaps (type creation, table
search/filter/sort, visual type indicators) with reasonably clean component
boundaries (`table-toolbar`, `chip-editor`, `new-type-form` are all
well-factored).

The main risks are: (1) the monolithic `ui.cljs` that will become
unmaintainable as features grow, (2) the full-rebuild-on-every-mutation
performance cliff, and (3) the localStorage persistence that will hit limits
with real usage. The `type-of-value` linear scan and `extend-type!`
rebuild-on-every-add are concrete examples of how these architectural issues
manifest in new code. These are all addressable without changing the fundamental
architecture.
