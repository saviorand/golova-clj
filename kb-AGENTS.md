# Working in this Golova knowledge base

This repo is a Golova kb: a triple-store PKM where **facts live in an
append-only event log** and **schema is declarative**. The repo is read by
a ClojureScript client that replays events into an in-memory db, so don't
think of these as plain data files — they're inputs to a deterministic build.

## File layout

    meta.edn                       UI state (selection, theme). Don't touch.
    domains/<id>/domain.edn        {:id :label :parent} — the domain entity.
    domains/<id>/events.edn        APPEND-ONLY fact log. THE source of truth.
    domains/<id>/types.edn         [{:name :constructors}] — declarative.
    domains/<id>/predicates.edn    [{:name :argTypes}] — declarative.
    domains/<id>/rules.edn         [bare Datalog clauses] — declarative.
    domains/<id>/queries.edn       [{:name :text :pinned?}] — declarative.
    domains/<id>/sources.edn       [{:name :kind :url :mapping}] — declarative.

"Declarative" = edit in place, no events needed. "Append-only" = add to the
end, never delete or modify existing entries.

## Adding facts (events.edn)

Append bare triples or full event maps. You do NOT need to:
- compute UUIDs (`:id` is auto-filled from a content hash)
- pick timestamps (`:at` defaults to 0)
- match the existing whitespace/key-order (the next client push normalizes)

```edn
;; minimum:
[:newperson :likes :coffee]

;; with provenance (recommended for batch imports):
{:op :assert :triple [:newperson :likes :coffee]
 :source "agent-import-2026-05-25"}

;; retract — never delete the original assert; append a retract instead:
{:op :retract :triple [:alice :note "old draft"] :source "agent-cleanup"}
```

Mixing bare triples and full event maps in one file is fine.

**Don't put `:domain-label` or `:domain-parent` events in events.edn** —
those belong in `domain.edn` (the importer synthesizes events from it).

## Adding schema (declarative files)

```edn
;; types.edn — add a constructor for a new entity:
[{:name "person" :constructors ["alice" "bob" "newperson"]}]

;; predicates.edn — declare a new attribute:
[{:name "parent" :argTypes ["person" "person"]}
 {:name "likes"  :argTypes ["person" "atom"]}]

;; rules.edn — append a Datalog rule:
[[(ancestor ?x ?y) [?x :parent ?y]]
 [(ancestor ?x ?z) [?x :parent ?y] (ancestor ?y ?z)]]

;; queries.edn — add a saved query:
[{:name "ancestors-of-alice" :text "(ancestor :alice ?d)" :pinned? true}]
```

Predicates auto-declare on first use, so you can often skip the
`predicates.edn` step when just adding facts. But you DO need to extend
`:constructors` in `types.edn` if you're using a new entity in a slot
typed as that constructor type (e.g. predicate `parent` with argTypes
`["person" "person"]` requires the new entity to be a `person` constructor).

## Declaring an external source (sources.edn)

A source is a declaration that fetches external data and projects it into
triples on Refresh (triggered from the UI). The user clicks Refresh; you
just author the declaration.

```edn
[{:name "starred-repos"              ;; identifier
  :kind :http-json                    ;; or :http-csv, :csv (file in repo)
  :url  "https://api.github.com/users/saviorand/starred"
  ;; :path "data/team.csv"            ;; for :kind :csv
  ;; :array-at :results               ;; for JSON wrapped in {:results [...]}
  :mapping
  {:each-as :item                     ;; template var bound to each record
   :id-from :item.full_name           ;; → entity ident (gets slugified)
   :triples [[:item :kind  :repo]     ;; LEFT=template var, MID=literal, RIGHT=lit-or-path
             [:item :name  :item.name]
             [:item :stars :item.stargazers_count]
             [:item :lang  :item.language]]}}]
```

Mapping resolution:
- `:item` alone → entity ident for the current record
- `:item.foo.bar` → path lookup (nested JSON or CSV column)
- anything else → literal

Each refresh emits events with deterministic ids (hash of source+entity+attr+value),
so re-running is idempotent. Refresh does NOT detect removals from upstream.

## Refactoring

- **Rename a value across all facts**: append retracts for old `[e a v]`,
  assert new `[e a v']` for each. Both stay in events.edn (the log is
  audit-able).
- **Rename a predicate**: edit `predicates.edn`. Existing facts still
  work; later, you can append retract+assert events to migrate facts.
- **Change a predicate's argTypes**: edit `predicates.edn`. May break
  existing facts that no longer satisfy the new types — check rebuild
  errors after the user pulls.
- **Move data between domains**: append retracts for the old `:in-domain`
  asserts, and new asserts for the destination domain.

## Before you commit

1. **Each .edn file must parse**. If you have a JVM:
   `clojure -e '(read-string (slurp "domains/<id>/events.edn"))'`
   (Or in Node: write a tiny script using `edn-data` if you must.)
2. **Inspect the diff**. If existing entries appear shifted around rather
   than just additions at the bottom, you've rewritten the file — back
   out and append-only instead.
3. **Commit message**: state what changed, what data was added, and where
   it came from. Example: `add 23 starred repos as facts under :code`.

## Sync caveats — communicate with the user

This repo is also written by clients (browser/mobile). To avoid clobbers:

- **After your push, the user should click _Pull_** (not Sync) in the
  client. Sync's conflict path can silently overwrite local client edits.
- **If a client has unsynced edits and you push concurrently**, those
  edits may be lost. Confirm with the user that all clients have Sync'd
  cleanly before you push.
- **Pretty-print normalization happens on first client push** after your
  commit — whitespace and key order may shift. This is one-time noise per
  agent commit; don't fight it.

## Don't (without asking)

- Modify or delete existing entries in `events.edn` (the log is append-only)
- Edit `meta.edn`
- Write `:domain-label` / `:domain-parent` events to `events.edn`
- Add raw event maps with hand-rolled `:id` values (let auto-fill do it
  unless you have a reason)
- Make changes affecting >100 facts without confirming with the user first
- Force-push, rebase shared history, or push to non-main branches the user
  syncs against
