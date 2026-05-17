# Golova

Notion-flavored knowledge-base UI on top of a Datahike backend, running
entirely in the browser (ClojureScript). No server, no native binary — distributes
as static files and installs as a PWA on Android.

## Stack

- **ClojureScript** via **Shadow CLJS**
- **Reagent** for views
- **Datahike** as the in-memory triple store
- Hand-rolled fixed-point rule materialiser (Datalog rules over Datahike)
- LocalStorage for persistence (event-sourced)

## What's where

```
src/golova/
  app.cljs       — entry point: boot, theme sync, keyboard shortcuts
  state.cljs     — domain model, rebuild, schema CRUD, persistence
  ui.cljs        — Reagent views (sidebar, topbar, scratch, predicate, type,
                   rule, query, entity, modal)
  storage.cljs   — localStorage backend + snapshot export/import
public/
  index.html
  style.css     — Notion-flavored light/dark theme
shadow-cljs.edn
deps.edn
package.json
```

## Run

```sh
npm install
npx shadow-cljs watch app
# then open http://localhost:8088/index.html
```

For a release build:
```sh
npx shadow-cljs release app
# public/ is now everything you need; serve it statically.
```

## Concepts

- **Domain** — a self-contained knowledge base (rules + facts) with its own
  event log and optional cross-domain imports. Switch domains via the sidebar.
- **Type** — a UI-level declaration: a name plus a list of constructors. Used
  to drive forms and column types. *Not enforced* by the engine.
- **Predicate** — a UI-level declaration: name + typed args. Discovered
  predicates (attributes that exist in the store without a declaration) are
  also surfaced.
- **Rule** — a Datalog rule, derived from a domain's program text. Edit via the
  "Rules" view (raw program text) or append via the "+ Rule" modal.
- **Saved query** — a named query body that lives in the sidebar and can be
  re-run with one click.
- **Entity** — an atom that appears as an argument anywhere. Click any atom-
  shaped value to open its entity page.

## Keyboard

- `⌘K` — focus the top query bar
- `⌘↵` in the Scratch editor — rebuild the program
- `Esc` — close modal

## Status

This is a first-cut port. Working: project setup, multi-domain model,
sidebar tree with types/predicates/rules/queries per domain, predicate table
view, type browser, rule view, saved-query view, entity page, scratch
(program editor with rebuild), top query bar, modal forms for create
flows, light/dark theme, localStorage persistence.

Next steps (not yet wired):
- Slash menu in scratch (insert facts/rules via `/`)
- Proof trees on derivation
- Cross-domain imports UI
- PWA manifest + service worker
- CSV / EDN import
