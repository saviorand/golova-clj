(ns golova.ui
  "Root layout + selection→view dispatch. Uses antd Layout for the
  overall page structure with a collapsible Sider and main Content area."
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
            [golova.ui.views.settings :refer [settings-view]]
            [golova.ui.antd :as antd]))

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
     [:div.app-layout
      (when mobile-open?
        [:div.sidebar-mobile-overlay
         {:on-click #(state/close-sidebar-mobile!)}])
      [sidebar]
      [:div.main-wrapper
       [topbar]
       [:div.main-content
        [main]]]]
     [modal]
     [popover]
     [palette]]))
