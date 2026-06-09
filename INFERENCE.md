# scasp-clj inference integration

## What was built

A full integration between golova-clj (ClojureScript/Datahike PKM) and
scasp-clj (pure Clojure s(CASP) — Answer Set Programming with deductive,
inductive, and abductive inference).

### Files added / changed

**`scasp-clj/src/scasp/*.cljc`** — renamed all `.clj` files to `.cljc` with
`#?(:clj … :cljs …)` reader conditionals for six JVM-specific spots:

- `term.cljc`: `Integer/parseInt` → `js/parseInt`
- `vars.cljc`: `Double/POSITIVE_INFINITY`, `Double/NEGATIVE_INFINITY`,
  `Math/floor`, `float?`, `long` cast
- `solver.cljc`: `catch clojure.lang.ExceptionInfo` → `:default`,
  `Math/pow`, `Math/abs`, integer casts
- `fold.cljc`: `Math/log`
- `output.cljc`: infinity constants

**`golova-clj/deps.edn`** — added scasp-clj src to `:paths` so shadow-cljs
finds the namespaces:

```edn
{:paths ["src" "/Users/supernaturval/Documents/GitHub/scasp-clj/src"] …}
```

**`src/golova/state/inference.cljs`** — new module. Public API:

```clojure
(run-inference! query opts)
;; Runs scasp over current DB facts + current domain's Datalog rules.
;; opts: :var-names, :predicate, :domain, :abducibles, :extra-rules, :max-results
;; Returns {:results […] :triples […] :stored-count n}

(domain-rules->scasp domain-id)
;; Converts Golova Datalog rules ([(head ?x) body…]) to scasp-clj rule maps.
;; Handles: triple patterns [?e :a ?v], NAF (not …), recursive rule calls.

(triples->scasp-facts triples)
;; Converts [e a v] DB triples to scasp-clj facts.

(current-db-as-facts)
;; Snapshot of entire DB as scasp facts.

(run-induction! ontology goal-kw opts)
;; Runs FOLD-R. Returns learned rules; does NOT auto-store.
```

Key behaviour: `run-inference!` automatically includes the current domain's
rules (converted from Golova Datalog format) alongside DB facts — no manual
`:extra-rules` needed for rules already in the domain.

**`src/golova/state.cljs`** — re-exports all inference fns.

**`src/golova/ui/modal.cljs`** — `:inference` modal case with:
- Deduction / Abduction mode radio
- Query EDN textarea
- Variable names field (comma-separated, e.g. `X` or `X,Y`)
- Store as predicate field (blank = infer from query op)
- Abducibles field (visible in Abduction mode; comma-separated functor
  strings, e.g. `vuln/1,misconfigured/1`)

**`src/golova/ui/palette.cljs`** — "Run inference (scasp)…" entry with ⊢ icon.

**`examples/inference-demo.edn`** — three-domain demo (Animals, Trust Network,
Risk Analysis) with sample queries in comments.

---

## How it works end-to-end

1. User opens ⌘K → "Run inference (scasp)…"
2. Modal collects query EDN + options
3. `run-inference!` builds a scasp program from:
   - All DB triples → arity-1/2 facts
   - Current domain's Datalog rules → scasp rules (variables `?x` → `"X"`)
4. `scasp/solve` runs the query
5. Results are extracted as `[e predicate v]` triples and appended as
   `:assert` events via `append-events!`
6. `rebuild!` replays events into a fresh Datahike DB — derived facts
   appear in the fact table

---

## What works

- Deductive inference with NAF (`(not [?x :type :penguin])`) — verified:
  `can-fly` returns sparrow, robin, eagle; tweety excluded
- Transitive closure (trust network, 5 results for alice's chain)
- Rule-body triple patterns and recursive calls convert correctly
- Derived facts stored as events, deduplicated, survive rebuild
- Abducibles field wired up in the UI (passed to `scasp/solve`)
- Modal shows stored count on success or error message on failure

---

## Known issues / what's missing

### 1. Golova Datalog rules don't materialise in the demo

**Symptom:** In Animals, saved queries "flyers (derived)" `[?x :can-fly true]`
return no results even though the `can-fly` Datalog rule is present.

**Root cause:** Golova's `materialize-rules` runs all rules from **all domains**
together in one flat list. When the Risk domain rules include ground-pattern
NAF like `(not [:system :unpatched :yes])`, or when some other rule produces a
`nil` entity ID during `d/db-with`, the transaction silently fails (caught by
`(catch :default _ [])`), and the entire materialisation pass for that iteration
is aborted — including `warm-blooded`, `can-fly`, `eats`, and `trusts`.

**Fix needed:** In `rebuild.cljs`, `materialize-rules` should apply each rule's
tx independently (not batch all rules into one `d/db-with` call), or at minimum
filter out datoms with nil/invalid entities before applying the tx. The
`expand-rule` function itself works correctly — the failure is in the batched tx
application.

Concretely in `materialize-rules`:
```clojure
;; current (broken for multi-domain with bad rules):
(let [tx (->> rules (mapcat #(expand-rule db %)) distinct vec)]
  (d/db-with db tx))   ; one bad datom aborts everything

;; fix: apply per-rule, catch individually:
(reduce (fn [db rule]
          (let [tx (expand-rule db rule)]
            (if (seq tx)
              (try (d/db-with db tx) (catch :default _ db))
              db)))
        db rules)
```

### 2. Saved queries in the demo use `true` for ref-typed predicates

**Symptom:** "unpatched systems" query `[?x :unpatched :yes]` works but was
originally written as `[?x :unpatched true]` which throws
"Cannot compare 35 to true" because `:unpatched` is `:db.type/ref` (stores
keyword `:yes`), not boolean.

**Status:** Fixed in the demo (`[?x :unpatched :yes]`). But the general issue is
that predicates declared with `["component" "atom"]` argTypes store keyword
values — queries must match on the keyword, not `true`.

### 3. Abduction not tested end-to-end

The Abduction mode is wired up in the UI (abducibles field appears, value is
passed to `scasp/solve`). The scasp solver supports abduction. However, no
end-to-end test has been run from the modal. The Risk domain demo was simplified
to deductive-only after the original abduction example was found to require
careful abducible setup.

**To test:** In Risk Analysis domain, run with mode=Abduction,
query `[{:op :breach :args [:system]}]`, abducibles `misconfigured/1` (after
removing the `:misconfigured :yes` seed fact).

### 4. Induction (FOLD-R) has no UI

`run-induction!` is implemented and exported but has no modal form. It returns
learned rules in scasp format but does not auto-store them. To use it, call
from the browser console:

```js
const c = cljs.core, kw = s => c.keyword(null, s);
golova.state.inference.run_induction_BANG_(ontology_rules, kw('target-pred'))
```

### 5. Derived facts show source "event" not "derived"

`triple-provenance` in `inspect.cljs` returns `:event` for anything in the
`:events` log and `:derived` only for datoms produced by Datalog rule
materialisation. Since scasp results are stored as `:assert` events, they show
`source = event` in the UI rather than `derived`. This is technically correct
(they are stored events) but visually indistinguishable from hand-asserted
facts.

**Fix:** Add a `:source "scasp"` tag to inference events (already done in the
code: `core/mk-event :assert … "scasp"`) and update `triple-provenance` to
check the event's `:source` field, returning `:derived` for `"scasp"` and
`"rule"` sources.

### 6. Rule converter limitations

`domain-rules->scasp` handles the common cases but skips:
- Rules with arity > 2 in the head (Golova materialiser also skips these)
- Aggregates, findall, forall in rule bodies
- Numeric comparison operators (`:>`, `:<`, etc.) in rule bodies — these would
  need to be mapped to scasp's CLP(R) operators

### 7. All-domain facts in scope

`current-db-as-facts` returns facts from the **entire** DB (all domains), not
just the current domain. This means inference over the Animals domain also sees
Trust and Risk facts, which can cause unexpected matches if predicate names
overlap across domains. A `:triples-in-domain` variant would scope facts
correctly.
