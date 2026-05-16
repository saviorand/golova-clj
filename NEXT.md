# Golova — next session

Context for picking this up cold.

## Where we are

- Built from scratch at `/Users/supernaturval/Documents/GitHub/golova-clj/`.
- Stack: ClojureScript + Shadow CLJS + Reagent + Naga + Datahike.
- Backend modules (`state.cljs`, `storage.cljs`, `proof.cljs`, Naga/Datahike
  adapter) were lifted from `/Users/supernaturval/Documents/GitHub/nagadatahiketest/spike/`
  and adapted — they're working code, no need to rebuild.
- UI written from scratch in Reagent to match the Notion-flavored Golova feel
  (the previous Mercury-backed prototype at `/Users/supernaturval/Documents/GitHub/golova/`).
- Light/dark theme, multi-domain sidebar with collapsible groups,
  per-domain Types / Predicates / Rules / Queries sub-sections, predicate
  table (read-only), type browser, rule view (read-only), saved-query view,
  entity pages, scratch with a single program-text editor.
- `rewrite-source` tolerates `;;` comments (auto-stripped) on top of Pabu's
  native `%`.

To run: `npm install && npx shadow-cljs watch app` → http://localhost:8089/index.html.

## Things to port from the old Mercury UI (`../golova/`)

These were polished features in v4 that haven't made the jump yet.

1. **Predicate-table add-row form** (high). Today the predicate view is
   read-only with only "× retract". Old UI had a `<tfoot>` with one input
   per column — dropdowns for declared enum types (with "+ new …"
   self-extending), text for primitives. This is the biggest "fill in a
   form, get a fact" win and the reason the predicate view exists.

2. **Inline cell-edit in the predicate table** (medium). Click a cell →
   becomes an input → Enter commits (retract old triple + assert new),
   Esc cancels. Old UI had this; the new view doesn't.

3. **Type icons in column headers** (small). `#` for int, `"` for string,
   `◇` for atom, `◆` for declared enum.

4. **Scratch as cells, not a giant textarea** (design choice — discuss
   before doing). The old UI's scratch was a notebook of cells (fact /
   rule / query / heading) with the slash menu. The new UI is one
   editable Naga program text. The cells UX was nicer for free-form
   exploration; the program-text UX is honest about what the engine
   actually consumes. Maybe the right move is *both*: a "Scratch cells"
   tab and a "Program" tab. The cells tab compiles cells into program
   text on rebuild.

5. **Slash menu** (depends on #4). In the cells world, typing `/` in a
   blank cell opened a Notion-style popover for Fact / Rule / Query /
   Heading. Old UI had this.

6. **Floating quick-add menu near a domain header** (small). Right now
   the "+" on a domain header opens a `quick-add` modal in the center
   of the screen. Old UI used a small popover anchored to the click —
   much less jarring.

7. **Dirty indicator on cells / textarea** (small). Old UI showed an
   orange border on cells with unsaved edits; the program-editor in the
   new UI has a status line but no border treatment.

8. **Move-to-domain via header pill** (medium). Old UI had a clickable
   domain pill on the type/predicate view header (`(no domain)` →
   prompt → save). New UI has no way to change the domain of a declared
   item except by re-declaring with the modal.

9. **Domain rename / delete UI** (medium). `state.cljs` has
   `rename-domain!` and `delete-domain!` but they're not exposed in the
   sidebar or anywhere. Right-click menu or a "…" affordance per domain
   header is fine.

10. **Solution truncation indicator** (small). Naga query results aren't
    capped today — for a personal KB it's fine, but adding `(take 200 ...)`
    plus "showing first N of M" matches the old UI's behavior.

## Improvements / fixes to the current build

11. **The hand-rolled query joiner in `state/run-query` is hacky.**
    It parses the body as a one-shot rule, then walks `:body` patterns,
    resolves each through the store, intersects bindings on shared
    variables. Works for trivial cases. Replace with a real Datahike
    `q` call (the store backs onto Datahike under the Naga adapter) or
    with `naga.engine/run` over a temp program — whichever is simpler.
    This affects the top query bar and saved-query view.

12. **The predicate table assumes arity 2.** Headers for undeclared
    predicates are hard-coded `subject / object`. For declared 3+arity
    predicates we'd need to either (a) reify multi-arity facts as
    Datahike entities with N attributes, or (b) accept that the data
    model is fundamentally triples and 3+arity is faked via reification.
    Pick (b) and add a "reified entity" mode where the form generates a
    fresh entity with N attributes.

13. **Multiple `for` seqs at the same level in the sidebar share the
    keyspace.** Reagent/React keys must be unique among siblings. If a
    user names a type and a saved query the same thing, React will warn.
    Easy fix: prefix keys (`(str "t-" name)`, `(str "q-" name)`).

14. **No "reset / clear" UI.** Today you have to wipe via
    devtools `localStorage.removeItem("golova.state.v1")`. Add a
    Settings or "danger zone" panel with explicit Reset.

15. **No snapshot export / import button.** `storage.cljs` already has
    `snapshot->json` and `parse-snapshot`; just need menu items.

16. **The starter program in `state.cljs` uses `%` comments now,**
    but the user's localStorage probably still has the `;;` version.
    `rewrite-source` strips them so it works — but worth knowing if
    debugging.

## Stretch (worth doing, not blocking)

17. **Proof trees.** `proof.cljs` is already in the repo (lifted from
    spike) and exposes `explain-once`. Wire a "why?" button on derived
    triples in the materialized-triples table → renders the supporting
    facts as a tree. Spike's version is in `spike/app.cljs` `claim-card`
    if it helps as a reference.

18. **Cross-domain imports UI.** `state.cljs` has `add-import!` /
    `remove-import!` / `known-predicates` — none surfaced. A small
    "Imports" panel in the domain view, picking another domain + which
    bare predicates to pull. Once imported they show up as `:other/foo`
    in the store.

19. **Templates picker.** Spike had family / reading / projects as
    install-on-first-run templates. We dropped that for a single starter.
    Add a "Load example" modal with the spike templates ported as
    Pabu programs.

20. **PWA manifest + service worker.** This was the original
    "install on Android" goal. Static files already work; add
    `public/manifest.webmanifest` and a minimal service worker that
    caches the JS + CSS. Then "Add to home screen" gives a real app
    icon.

21. **Mobile sidebar.** CSS has a hide-the-sidebar `@media (max-width: 800px)`
    rule but no way to open it. Hamburger button in the topbar that
    slides the sidebar in.

22. **Atom-shape detection broader.** `navigable?` only treats keywords as
    linkable. Strings that look like atoms (lowercase ident) could also be
    linkable, but right now they aren't — Naga atoms in Pabu are stored as
    keywords so it usually works, just confirm.

## Architectural choices to revisit when there's time

- **The "schema" map on each domain (types / predicates / queries) is
  pure UI metadata** — the engine doesn't enforce types or arities. This
  was deliberate (matches spike's approach), but at some point you may
  want soft validation on assert ("warn: declared `age :: person, int`
  but you're asserting `age("alice", 70)` — `"alice"` is a string, not a
  person"). Reasonable to defer; reasonable to add a non-blocking warning
  banner per malformed fact.

- **One program text per domain or many?** Spike does one. We followed.
  An alternative: keep rules as first-class entities (their own
  edit surface in the UI) and merge them into a program text only at
  rebuild time. The cells idea above is a step in this direction.

- **Did we want the entity page to be a slide-in panel or full view?**
  Today it's a full main-area replacement. A right-side slide-in (Notion
  inline-database row-as-page idiom) is nicer for "hop into Alice, then
  back to where I was" workflows.

## Quick "first 30 minutes" plan if you want one

1. Add the predicate-table add-row form (item #1) — biggest UX win.
2. Wire up domain rename + delete (item #9).
3. Replace the quick-add modal with a floating menu (item #6).
4. Add a Reset / Export pair in a small settings overlay (items #14, #15).
5. Stop and try a real flow: create domain → declare type → declare
   predicate → add 5 facts via the form → write a rule in scratch →
   query in the top bar. See what breaks.
