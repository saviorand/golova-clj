(ns golova.state
  "Public façade for the application state. Implementation lives in
  `golova.state.*` submodules; this namespace re-exports the vars
  callers reach for via `state/foo`, so UI code never needs to know
  which submodule a function actually lives in.

  Layout of the submodules:

    core     — app-state atom, mk-event, save!, serializable
    schema   — Datahike schema derivation, attr/value-type helpers
    rebuild  — tx + rule materialisation, rebuild!, append-events!
    persist  — load/save, v1→v2 migration, starter snap, reset, snapshot
    domain   — domain CRUD + read-only queries
    facts    — assert/retract triples, notes, form coercion
    decl     — declare-type/predicate/query, set-rules!, move-*
    ui       — selection, theme, sidebar expansion, modal, palette
    inspect  — all-triples, mentions, backlinks, recent activity
    query    — Datalog query runner
    csv      — CSV preview + import
    convert  — schema conversions (scalars → refs/enums), cleanup, plans"
  (:require [golova.state.core :as core]
            [golova.state.schema :as schema]
            [golova.state.rebuild :as rebuild]
            [golova.state.persist :as persist]
            [golova.state.domain :as domain]
            [golova.state.facts :as facts]
            [golova.state.decl :as decl]
            [golova.state.ui :as ui]
            [golova.state.inspect :as inspect]
            [golova.state.query :as query]
            [golova.state.csv :as csv]
            [golova.state.convert :as convert]
            [golova.state.sync :as sync]))

;; ---------------------------------------------------------------------------
;; core

(def app-state    core/app-state)
(def current-id   core/current-id)
(def serializable core/serializable)
(def save!        core/save!)

;; ---------------------------------------------------------------------------
;; schema

(def attr-schema-info schema/attr-schema-info)
(def db-type-label    schema/db-type-label)

;; ---------------------------------------------------------------------------
;; rebuild

(def rebuild! rebuild/rebuild!)

;; ---------------------------------------------------------------------------
;; persist

(def load-or-seed!   persist/load-or-seed!)
(def reset-all!      persist/reset-all!)
(def import-snapshot! persist/import-snapshot!)
(def apply-program!  persist/apply-program!)

;; ---------------------------------------------------------------------------
;; domain

(def domains-list           domain/domains-list)
(def domain-info            domain/domain-info)
(def subdomains-of          domain/subdomains-of)
(def ancestor-of?           domain/ancestor-of?)
(def atoms-in-domain        domain/atoms-in-domain)
(def declared-types-in      domain/declared-types-in)
(def declared-predicates-in domain/declared-predicates-in)
(def queries-in             domain/queries-in)
(def rules-in               domain/rules-in)
(def create-domain!         domain/create-domain!)
(def delete-domain!         domain/delete-domain!)
(def rename-domain!         domain/rename-domain!)
(def move-domain-parent!    domain/move-domain-parent!)
(def find-or-create-domain! domain/find-or-create-domain!)

;; ---------------------------------------------------------------------------
;; facts

(def assert-triple!    facts/assert-triple!)
(def retract-triple!   facts/retract-triple!)
(def replace-triple!   facts/replace-triple!)
(def set-note!         facts/set-note!)
(def declared-type?    facts/declared-type?)
(def coerce-value      facts/coerce-value)
(def extend-type!      facts/extend-type!)
(def assert-from-form! facts/assert-from-form!)

;; ---------------------------------------------------------------------------
;; decl

(def declare-type!      decl/declare-type!)
(def delete-type!       decl/delete-type!)
(def declare-predicate! decl/declare-predicate!)
(def delete-predicate!  decl/delete-predicate!)
(def save-query!        decl/save-query!)
(def delete-query!      decl/delete-query!)
(def toggle-pin-query!  decl/toggle-pin-query!)
(def pinned-queries     decl/pinned-queries)
(def set-rules!         decl/set-rules!)
(def move-type!         decl/move-type!)
(def move-predicate!    decl/move-predicate!)
(def move-query!        decl/move-query!)

;; ---------------------------------------------------------------------------
;; ui

(def toggle-sidebar-mobile! ui/toggle-sidebar-mobile!)
(def close-sidebar-mobile!  ui/close-sidebar-mobile!)
(def select!            ui/select!)
(def go-home!           ui/go-home!)
(def switch-domain!     ui/switch-domain!)
(def toggle-domain!     ui/toggle-domain!)
(def expand-domain!     ui/expand-domain!)
(def toggle-subsection! ui/toggle-subsection!)
(def expand-subsection! ui/expand-subsection!)
(def set-theme!         ui/set-theme!)
(def toggle-onboarding! ui/toggle-onboarding!)
(def open-modal!        ui/open-modal!)
(def close-modal!       ui/close-modal!)
(def open-palette!      ui/open-palette!)
(def close-palette!     ui/close-palette!)
(def toggle-palette!    ui/toggle-palette!)
(def set-palette-query! ui/set-palette-query!)
(def palette-move!      ui/palette-move!)
(def palette-set-index! ui/palette-set-index!)

;; ---------------------------------------------------------------------------
;; inspect

(def all-triples         inspect/all-triples)
(def triples-in-domain   inspect/triples-in-domain)
(def entity-mentions     inspect/entity-mentions)
(def entity-note         inspect/entity-note)
(def entity-domain       inspect/entity-domain)
(def all-atom-idents     inspect/all-atom-idents)
(def untyped-atoms       inspect/untyped-atoms)
(def entities-with-notes inspect/entities-with-notes)
(def note-backlinks      inspect/note-backlinks)
(def recent-events       inspect/recent-events)
(def triple-provenance   inspect/triple-provenance)

;; ---------------------------------------------------------------------------
;; query

(def run-query query/run-query)

;; ---------------------------------------------------------------------------
;; csv

(def csv-preview csv/csv-preview)
(def import-csv! csv/import-csv!)

;; ---------------------------------------------------------------------------
;; convert

(def convert-predicate-to-refs!  convert/convert-predicate-to-refs!)
(def convert-predicate-to-enum!  convert/convert-predicate-to-enum!)
(def drop-predicate-with-facts!  convert/drop-predicate-with-facts!)
(def predicate-fact-count        convert/predicate-fact-count)
(def cleanup-empty-predicates!   convert/cleanup-empty-predicates!)
(def apply-conversion-plan!      convert/apply-conversion-plan!)

;; ---------------------------------------------------------------------------
;; sync

(def snapshot->files        sync/snapshot->files)
(def files->snapshot        sync/files->snapshot)
(def sync-pull!             sync/sync-pull!)
(def sync-push!             sync/sync-push!)
(def sync!                  sync/sync!)
(def bind-sync-triggers!    sync/bind-sync-triggers!)
(def unbind-sync-triggers!  sync/unbind-sync-triggers!)
