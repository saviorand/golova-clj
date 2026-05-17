---
name: reagent-form2-prop-staleness
description: Form-2 Reagent components silently capture their props at mount time. Use this when a view "doesn't update" when you click a different sidebar item — almost always the inner fn signature is wrong or the local atom never resets.
---

# Reagent form-2 prop-staleness pattern

A common, sneaky bug. Symptom: you click "Saved query A" then click
"Saved query B" in the sidebar; the view title updates but the textarea
still shows A's body.

## The two bugs that cause it

### 1. Inner fn drops props

A form-2 component is:

```clojure
(defn my-view [some-prop]   ;; outer — runs ONCE per mount
  (let [local (r/atom ...)]
    (fn [some-prop]         ;; inner — re-runs on every render
      ...)))
```

If you write `(fn [])` instead of `(fn [some-prop])`, the inner function
captures `some-prop` from the outer closure — frozen at mount time.
Reagent re-invokes with the new prop but you ignore it.

**Fix:** declare every prop on the inner fn signature, even if you also
use the outer-let `name` in places.

### 2. Local atom never resets when prop changes

The outer `let` runs once. If you initialize `local` from the prop:

```clojure
(let [local (r/atom {:text (:text q)})]
  (fn [name] ...))
```

…then switching to a different `name` keeps the old `local` data. The
inner re-runs but never touches `local`.

**Fix:** track which prop the atom was loaded for, reset when it changes:

```clojure
(defn my-view [name]
  (let [local (r/atom {:text nil :loaded-name nil})]
    (fn [name]
      (let [q (lookup name)]
        ;; reset when name prop changes
        (when (not= name (:loaded-name @local))
          (reset! local {:text (:text q) :loaded-name name}))
        ...))))
```

This is the same idea as React's `useEffect([name])` reset.

## Examples in Golova

- `query-view` — `:loaded-name` tracker, classic case
- `rules-view` — `:loaded` tracks current-domain, resets text on switch
- `entity-view` — resets ui-state when entity changes (chip-editor in
  type-view does this too via `:loaded-name` + `:last-len`)

## When to use form-2 vs form-1

If the component owns local state (input drafts, editing flags), form-2.
If it's pure render over `@app-state`, form-1.

Pure form-1 components are simpler — they auto-handle prop changes
because they re-run from scratch every time. Reach for form-2 only when
you genuinely need persistent local state across renders.

## Force-remount alternative

Adding a `^{:key name}` metadata on the call site forces React to unmount
+ remount when `name` changes. Cleaner than the loaded-name reset but
slightly more expensive (loses any in-progress edits). Pick based on
whether you want edits to survive prop changes.

```clojure
:query     ^{:key (:name sel)} [query-view (:name sel)]
```

Either approach works — Golova uses the reset pattern because it's
explicit about *what* state survives.
