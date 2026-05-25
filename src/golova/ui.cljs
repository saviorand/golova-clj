(ns golova.ui
  "Root layout + selection→view dispatch. Everything else lives in
  golova.ui.* sub-namespaces:

    common.cljs       — fmt-val, atom-link, pred-link, markdown, fmt-relative
    table.cljs        — table-toolbar, sort-key, sort-indicator, cycle-sort
    typed.cljs        — type-value, chip-editor, typed-input, ctor-map
    derivations.cljs  — domain-predicates/rules, rule-clause, rules-using/defining
    popover.cljs      — popover state + view, move-to-pill
    sidebar.cljs      — left-rail domain tree
    topbar.cljs       — Datalog query bar
    home.cljs         — quick-scratch, pinned queries, activity feed, glossary
    palette.cljs      — ⌘K command palette
    modal.cljs        — every modal form + dispatch

    views/rules.cljs      — \"Rules / facts\" overview
    views/predicate.cljs  — predicate facts page
    views/type.cljs       — type editor
    views/rule.cljs       — single-rule page
    views/query.cljs      — saved query page
    views/entity.cljs     — entity (atom) page"
  (:require [golova.state :as state :refer [app-state]]
            [golova.ui.sidebar :refer [sidebar]]
            [golova.ui.topbar :refer [topbar]]
            [golova.ui.home :refer [home-view]]
            [golova.ui.modal :refer [modal]]
            [golova.ui.popover :refer [popover]]
            [golova.ui.palette :refer [palette]]
            [golova.ui.views.rules :refer [rules-view]]
            [golova.ui.views.predicate :refer [predicate-view]]
            [golova.ui.views.type :refer [type-view]]
            [golova.ui.views.rule :refer [rule-view]]
            [golova.ui.views.query :refer [query-view]]
            [golova.ui.views.source :refer [source-view]]
            [golova.ui.views.entity :refer [entity-view]]
            [golova.ui.views.domain :refer [domain-view]]
            [golova.ui.views.settings :refer [settings-view]]))

(defn main
  "Choose the main view based on (:selection app-state)."
  []
  (let [s @app-state
        sel (:selection s)]
    (case (:kind sel)
      :home      [home-view]
      :type      [type-view (:name sel)]
      :predicate [predicate-view (:name sel) (:arity sel)]
      :rule      [rule-view (:name sel)]
      :query     [query-view (:name sel)]
      :source    [source-view (:name sel)]
      :entity    [entity-view (:name sel)]
      :domain    [domain-view (or (:domain sel) (:current-domain s))]
      :settings  [settings-view]
      (if (:current-domain s)
        [rules-view]
        [home-view]))))

(defn root []
  (let [mobile-open? (:sidebar-mobile-open? @app-state)]
    [:<>
     [sidebar]
     (when mobile-open?
       [:div.sidebar-overlay
        {:on-click #(state/close-sidebar-mobile!)}])
     [:main
      [topbar]
      [main]]
     [modal]
     [popover]
     [palette]]))
