---
name: datahike-cljs-gotchas
description: Sharp edges in Datahike-CLJS that hit Golova during the Naga→Datahike port. Read before touching state.cljs's schema, transaction, or query code — these are all things that compile clean but break at runtime or silently return wrong results.
---

# Datahike CLJS gotchas (learned during the Naga port)

Datahike's `datahike.core` namespace works in CLJS but has real bugs and
schema-validation rules that aren't obvious. The patterns Golova settled
on are below; deviate from them at your peril.

## Schema: `:db/valueType` is more restrictive than Datomic

Only `:db.type/ref` and `:db.type/tuple` are accepted. Trying
`{:age {:db/valueType :db.type/long}}` throws:

```
Bad attribute specification for {:age {:db/valueType :db.type/long}},
expected one of #{:db.type/tuple :db.type/ref}
```

The fix: **omit `:db/valueType` for scalars**. Datahike infers the type from
the transacted value. Only declare `:db/valueType :db.type/ref` for
attributes that point at other entities. See `predicate-schema` in
`state.cljs`.

## `:db/cardinality` is the one schema field you do need

Without an entry, an attribute defaults to cardinality `:one` — which silently
overwrites prior values. Declare `:many` for any relation that allows
multiple values (parent, in-genre, etc.).

## Named entities: `:db/ident`, not `:atom/name`

Datahike's built-in `:db/ident` is unique-identity by definition. Pre-create
atoms with `{:db/ident :alice}` and then refer to them as the bare keyword
`:alice` in any ref position or `:where` clause. This is way cleaner than
inventing a `[:atom/name :alice]` lookup ref.

```clojure
;; transact:
(d/db-with db [{:db/ident :alice}
               {:db/ident :bob}
               [:db/add :alice :parent :bob]])

;; query:
(d/q '[:find ?p :where [:alice :parent ?p]] db)
;; => #{[2]}  ;; numeric eid
```

To turn an eid back into its ident: `(d/datoms db :eavt eid :db/ident)`
returns the datom with `:v` = the keyword.

## Rules: the engine has a `demand_set.size` bug

Passing rules as the third arg to `d/q` (the `%` slot) blows up with:

```
TypeError: demand_set.size is not a function
```

…inside `datahike.query.execute/inject-magic-relation` — the CLJS branch
expects a JS Set but gets a CLJS set. **Don't pass rules at query time.**

Golova's workaround: **materialise rules to fixed-point during rebuild**.
`expand-rule` runs each rule body as a plain query, asserts the head as
real facts, loops until nothing new is derived. Then queries are plain
Datalog without `:in $ %`. See `materialize-rules` in `state.cljs`.

Bonus: rule heads also get auto-installed in the schema (see
`rule-head-attrs` + `build-schema`) so the materialised triples land in
attrs Datahike knows about.

## EAV direction matters — name your relations carefully

The triple `[:zeke :parent :alice]` is *one* keyword/keyword pair; there's
no inherent meaning. **Golova's convention: subject IS the relation** —
read it as "zeke is parent of alice". This bit me on the cousin rule:

```clojure
;; WRONG — derives self-cousins (alice→alice etc.)
[(cousin ?x ?y) [?x :parent ?p1] [?y :parent ?p2] [?p1 :sibling ?p2]]

;; RIGHT — `?p1 :parent ?x` means "p1 is parent of x", so p1 IS x's parent
[(cousin ?x ?y) [?p1 :parent ?x] [?p2 :parent ?y] [?p1 :sibling ?p2]]
```

When debugging a rule that returns the wrong rows, look at this first. The
"3 self-pairs only" output is the canonical symptom of an inverted pattern.

## Rule body syntax: rule-calls vs attr patterns

Users naturally write `(ancestor ?y ?z)` (rule-call) but the materialiser
runs the body as a plain `d/q`. The trick: `rewrite-rule-call` syntactically
rewrites `(name ?a ?b)` → `[?a :name ?b]` inside body clauses before
querying. So users can write either form.

In `run-query`, the same rewrite is applied so saved queries can use either
form too. Materialised attr patterns are always available since
`materialize-rules` stored them as real facts.

## Arity-1 rules

Encoded as boolean attributes: `[(loved ?b) [?b :rating 5]]` materialises
as `[?b :loved true]`. Schema entry is `{:loved {:db/cardinality :one}}` (no
ref-type). Saved queries use `[?b :loved true]`.

There is no `:rdf/type :loved` Pabu-style encoding anywhere anymore — that
was the Naga era.

## Lookup refs in `:where` clauses don't work

Datahike CLJS rejects `[?b :recommended-by [:atom/name :alice]]`. With
`:db/ident`, write `[?b :recommended-by :alice]` instead — the bare
keyword auto-resolves. The example data uses this convention everywhere.

## Cardinality :many transactions

You can transact `[:db/add :alice :parent :bob]` and then
`[:db/add :alice :parent :carol]` — both stick because `:parent` is
cardinality `:many`. With `:one`, the second overwrites the first.

## Index access for fast lookups

For "all triples about entity X" use index lookups directly instead of
filtering all datoms:

```clojure
(d/datoms db :eavt :alice)          ;; everything where :alice is the entity
(d/datoms db :aevt :parent)         ;; all parent datoms
(d/datoms db :eavt :alice :parent)  ;; alice's parents
```

`d/q` is also fine but for the entity-page mention list and similar
single-entity lookups, datom access is simpler and faster.

## Test before assuming

Datahike's CLJS port lags the JVM version. If something compiles but
behaves weirdly, first reach for `js/console.log` on the parsed query / tx
data, then see whether the JVM version of the same code would work. Most
"is Datahike broken?" sessions end with "yes it was, and the workaround is
in `state.cljs`."
