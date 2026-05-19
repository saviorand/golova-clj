---
name: csv-to-knowledge-base
description: Turn a CSV (e.g. a Notion DB export) into a proper Golova knowledge base — convert flat string columns into cross-domain refs and in-domain enums, then layer rules + saved queries on top. Use when the user gives you a CSV and asks to "import and model" or "translate into domains/rules/queries."
---

# CSV → Golova knowledge base

End-to-end recipe for turning a tabular CSV into something queryable: cross-domain
refs for first-class entities, in-domain enums for fixed vocabularies, derived
rules for the relations the data implies, saved queries for the questions worth
pinning.

## The three artifacts

For a CSV named e.g. `People.csv`, produce three things:
1. **The CSV** (already exists) — user imports via Settings → Import CSV.
2. **`<name>-cleanup.edn`** — conversion plan. Example: `examples/people-db-cleanup.edn`.
3. **`<name>-program.edn`** — rules + saved queries. Example: `examples/people-db-program.edn`.

User applies in order via Settings → Apply plan → Import rules & queries.
**Order matters**: the cleanup plan creates the new domains (Organizations,
Places, etc.) by converting predicates. The program then references those
domains. Apply the program before the plan and any `:domain "Organizations"`
entries in the program will spawn empty phantom domains via
`find-or-create-domain!`.

## Step 1 — inspect the CSV

Before writing anything, look at the actual data. Use Python (`csv.DictReader`
handles quoted commas — `awk -F,` doesn't):

```python
import csv
from collections import Counter
with open(path, encoding='utf-8-sig') as f:
    r = csv.reader(f); headers = next(r); rows = list(r)
for i, h in enumerate(headers):
    vals = [r[i].strip() for r in rows if r[i].strip()]
    print(h, len(vals), 'distinct=', len(Counter(vals)))
```

Note: count + distinct per column tells you which to drop (zero non-empty),
which are categorical (low distinct), which are freeform (high distinct ≈ count).
For categorical-ish columns, also dump the top-N values to see if they look
like a fixed vocab or open-ended.

## Step 2 — categorize each column

| Pattern | Convert to | Example |
|---|---|---|
| First-class entity that exists outside this CSV | **Cross-domain ref**, new domain | `:affiliations` → Organizations |
| Two columns naming the same kind of thing | **Cross-domain ref**, shared domain | `:affiliations` + `:source` both → Organizations |
| Small fixed vocab (High/Med/Low, Yes/No) | **In-domain enum** (`:to-domain :same`) | `:priority`, `:reach-out` |
| Long-tail categorical (job titles, expertise tags) | **Cross-domain ref**, new domain — accept singletons | `:profession-position` → Roles |
| Freeform text (notes, URLs, phones, emails) | **Stay string** — leave it | `:notes`, `:linkedin` |
| Numeric measurements | **Stay int** (importer already typed it) | `:age` |
| All blank, noise, or sparse | **Drop** | `:met-question`, Notion's `"Empty"` columns |

When in doubt, ref is right for anything whose values you'd want to navigate
to or attach notes/relations to later.

## Step 3 — write the cleanup plan

```clojure
[{:op :convert :attr :affiliations
  :to-domain "Organizations" :enum "organization"}
 {:op :convert :attr :source
  :to-domain "Organizations"}                    ; reuse existing
 {:op :convert :attr :priority
  :to-domain :same :enum "priority"}             ; in-domain enum
 {:op :drop :attr :met-question}                 ; specific predicate
 {:op :drop-empty}]                              ; sweep empties last
```

- `:to-domain` accepts a string (find-or-create by label), keyword (existing id), or `:same` (predicate's current domain).
- `:enum "name"` declares an enum type grouping the new atoms — drives the colored pills + dropdown in the add-row form.
- Plan is **idempotent** — re-running on already-converted data is a no-op.
- Predicates missing from the data **silently no-op**, so one plan covers variant CSVs.
- Always put `:drop-empty` last.

## Step 4 — write the program

```clojure
{:rules
 [{:domain "People"
   :clause [(colleague ?a ?b)
            [?a :affiliations ?o]
            [?b :affiliations ?o]]}]
 :queries
 [{:domain "People" :name "high priority"
   :text "[?p :priority :high]"}
  {:domain "People" :name "inner circle"
   :text "(inner-circle ?p)"
   :pinned? true}]}
```

### Rule patterns

| Shape | Use for |
|---|---|
| `(shared-X ?a ?b) [?a :foo ?x] [?b :foo ?x]` | "Two entities that share a foo" — colleagues, neighbors, peers |
| `(flag ?p) [?p :a :x] [?p :b :y]` | Arity-1 boolean combining conditions |
| `(transitive ?a ?c) [?a :rel ?b] (transitive ?b ?c)` | Transitive closure — needs explicit entity-to-entity relations to fire |

### Query patterns

| Query | Reads as |
|---|---|
| `[?p :priority :high]` | Direct attribute match — keyword resolves to ident on ref attrs |
| `(colleague ?p :alan-fortuny)` | Parametric — substitute the keyword |
| `(inner-circle ?p)` | Arity-1 rule-call sugar, rewrites to `[?p :inner-circle true]` |
| `[?p :affiliations ?o] [?o :label ?name]` | Cross-domain join — pull `:label` for human-readable output |

## Gotchas

- **Slug values**: `"High"` becomes `:high` post-conversion. Rules and queries use the slug, not the original string.
- **`:label` is preserved**: every atom from `convert-to-refs` gets `[<atom> :label "Original String"]`. Casing/punctuation isn't lost; join through `[?o :label ?n]` for display.
- **Cross-domain refs are just refs**: `:florian-stoger-drums` in Organizations is referenced from People's `:affiliations` like any keyword. No qualified names, no `:imports`.
- **Predicates are global**: if two CSVs declare `:status` with different shapes, the first declaration wins. Rename one before importing the second.
- **Two cols → one domain**: do the first `:convert` op with `:enum "name"` to create the type, then the second op as plain `:to-domain "SameLabel"` (no `:enum`). The conversion engine dedupes via slugify, merging values into the existing type's constructors.

## Integrating with existing data

When the user already has a Golova knowledge base and is importing a CSV that
overlaps with what's there, the workflow changes — Step 1 grows a pre-flight
inspection of the current state, and the cleanup plan reuses existing
domains/types instead of creating duplicates.

### Pre-flight: inspect what already exists

Before classifying columns, list the current domains and their declared types
+ predicates so you know what to reuse. Quickest way: import the user's
`golova.state.v1` localStorage snapshot or read `(domains-list)` /
`[:schema :types]` / `[:schema :predicates]` via the console.

For each new CSV column, ask:
1. Does a domain with this shape **already exist**? (e.g. importing a Books DB
   where `:publisher` looks like an Org — there might already be an
   Organizations domain from a prior People import.)
2. Does a declared **type** for these values already exist? (e.g. importing a
   second CSV with a Priority column — reuse the existing `priority` enum
   instead of declaring a parallel one.)
3. Does a **predicate name** already exist with a different shape? (e.g.
   existing `:status` is `ref` to `task-status`; the new CSV has a `:status`
   that's free text — they will collide. Rename one before importing.)

### Targeting existing domains in the plan

Use a **keyword** in `:to-domain` to point at an existing domain by id:

```clojure
[{:op :convert :attr :publisher
  :to-domain :organizations}              ; existing domain — atoms merge in
 {:op :convert :attr :recommended-by
  :to-domain :people}]                    ; references atoms already in People
```

The id is whatever `domains-list` shows for that domain (slugified from the
original label, e.g. `:organizations`, `:people-db-cleaned`). If you pass a
**string**, `find-or-create-domain!` will reuse a domain matching by label,
but a string is risky when the user has multiple similarly-named domains;
prefer the keyword id for clarity.

### Reusing existing enum types

If a `priority` enum already exists in the People domain and a new CSV's
`:urgency` column has the same High/Medium/Low values, two options:

- **Reuse the existing type** by omitting `:enum` and pointing `:to-domain` at
  the same domain: `{:op :convert :attr :urgency :to-domain :people}`. The
  conversion engine slugifies values into existing constructor names — if
  `:high`/`:medium`/`:low` already exist as atoms, they're reused (no new
  atoms, no duplicate constructors). The `:urgency` predicate becomes ref-typed.
- **Declare a new type alongside** if the values are semantically distinct
  (e.g. `:urgency` = Critical/Soon/Whenever): `:enum "urgency"`.

### Cross-CSV refs (referencing existing atoms)

The common pattern: CSV B has a column whose values name entities that already
exist in domain A. Example: importing a "Books I've read" CSV with a `Recommended by`
column that contains names from the existing People domain.

Convert that column with `:to-domain :people`. The slugify produces keywords
that may or may not match existing People atoms:

- **If they match** (e.g. "Alan Fortuny" → `:alan-fortuny`, which exists),
  the ref points at the existing atom. The book becomes connected to that
  person. Cross-CSV graph traversal works immediately.
- **If they don't match** (e.g. CSV has "AlanF" → `:alanf`), a new singleton
  atom is created. You'll see two near-duplicates in the People domain. Fix
  via Quick-K rename or a one-off retract/assert event.

Tell the user to **check slug alignment** before running the plan when
cross-CSV refs are intended. The simplest check: take the unique values of
the new column, slugify them, intersect with `(atoms-in-domain :people)`,
and report the mismatches.

### Idempotency vs re-import

The conversion plan itself is idempotent. But **re-importing the same CSV
file** appends fresh events for every cell — entities deduplicate by slug
(`:db.cardinality/one` overwrites), but events accumulate in the log. For
add-only updates that's fine. For "I edited the CSV and want to re-import,"
the right path is:

1. Wipe the domain via the domain menu (or selectively retract the predicates
   you're re-importing — `:op :drop` per predicate)
2. Re-import the CSV
3. Re-apply the cleanup plan (idempotent — no harm even if some predicates
   are already ref-typed from a previous run)
4. Re-import the program (queries upsert by name, rules append — dedupe
   manually if needed)

## When to use this skill

The user gives you a CSV (or points at one) and asks to "import," "model as a
domain," "clean up the import," or similar. The skill applies to any
Notion-style flat CSV — contacts, books, projects, etc. — not just People.

For each new CSV, follow Steps 1→4 in order. Don't skip Step 1 — without
looking at the data you'll either over-classify common scalars or miss
columns that should be refs.
