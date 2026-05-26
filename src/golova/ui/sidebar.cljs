(ns golova.ui.sidebar
  "Left sidebar using antd Menu for the domain tree navigation.
  Features: collapsible domain tree with nested submenus for types,
  predicates, rules, queries, and notes. Home link and settings at
  the bottom."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            ["@ant-design/icons" :as icons]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [fmt-val]]
            [golova.ui.derivations :refer [domain-predicates domain-rules]]
            [golova.ui.popover :refer [open-popover-from-event!]]
            [golova.ui.antd :as antd]))

(defn- kind-icon
  "Return an antd icon component for a given navigation kind."
  [kind]
  (case kind
    :home       [antd/HomeOutlined]
    :rules      [antd/CodeOutlined]
    :type       [antd/AppstoreOutlined]
    :predicate  [antd/ApiOutlined]
    :rule       [antd/BranchesOutlined]
    :query      [antd/QuestionCircleOutlined]
    :source     [antd/DatabaseOutlined]
    :entity     [antd/FileOutlined]
    :domain     [antd/FolderOutlined]
    :note       [antd/EditOutlined]
    :settings   [antd/SettingOutlined]
    [antd/FileOutlined]))

(defn- domain-menu-items
  "Build antd Menu items for a single domain's sub-items."
  [domain-id {:keys [selection current-domain]}]
  (let [on-domain? (= domain-id current-domain)
        sel-kind   (:kind selection)
        sel-name   (:name selection)
        preds      (domain-predicates domain-id)
        decl-pred  (filter :declared? preds)
        disc-pred  (filter (complement :declared?) preds)
        all-preds  (concat decl-pred disc-pred)
        rules      (domain-rules domain-id)
        types      (state/declared-types-in domain-id)
        queries    (state/queries-in domain-id)
        sources    (state/sources-in domain-id)
        notes      (state/entities-with-notes domain-id)
        active?    (fn [kind & [name]]
                     (and on-domain?
                          (= kind sel-kind)
                          (or (nil? name) (= name sel-name))))]
    (vec
     (concat
      ;; Rules / facts overview
      [{:key    (str "rules:" (name domain-id))
        :icon   (r/as-element [antd/CodeOutlined])
        :label  "Rules / facts"
        :class  (when (active? :rules) "ant-menu-item-selected-custom")
        :onClick #(do (state/switch-domain! domain-id)
                      (state/select! {:kind :rules :domain domain-id}))}]

      ;; Types
      (when (seq types)
        [{:key     (str "types:" (name domain-id))
          :icon    (r/as-element [antd/AppstoreOutlined])
          :label   (str "Types (" (count types) ")")
          :children
          (for [t types]
            {:key   (str "type:" (name domain-id) ":" (:name t))
             :label [:span.nav-label
                     [:span (:name t)]
                     [:span.nav-count (count (:constructors t))]]
             :onClick #(do (state/switch-domain! domain-id)
                           (state/select! {:kind :type
                                           :name (:name t)
                                           :domain domain-id}))})}])

      ;; Predicates
      (when (seq all-preds)
        [{:key     (str "preds:" (name domain-id))
          :icon    (r/as-element [antd/ApiOutlined])
          :label   (str "Predicates (" (count all-preds) ")")
          :children
          (for [p all-preds]
            {:key   (str "pred:" (name domain-id) ":" (:name p) "/" (:arity p))
             :label [:span.nav-label
                     [:span
                      (if (:declared? p) "▦ " "▢ ")
                      (:name p) "/" (:arity p)]
                     [:span.nav-count (count (:facts p))]]
             :onClick #(do (state/switch-domain! domain-id)
                           (state/select! {:kind :predicate
                                           :name (:name p)
                                           :arity (:arity p)
                                           :domain domain-id}))})}])

      ;; Rules
      (when (seq rules)
        [{:key     (str "d-rules:" (name domain-id))
          :icon    (r/as-element [antd/BranchesOutlined])
          :label   (str "Rules (" (count rules) ")")
          :children
          (for [r rules]
            {:key   (str "rule:" (name domain-id) ":" (:name r) "/" (:arity r))
             :label [:span.nav-label
                     [:span "ƒ " (:name r) "/" (:arity r)]
                     (when (> (count (:clauses r)) 1)
                       [:span.nav-count (count (:clauses r))])]
             :onClick #(do (state/switch-domain! domain-id)
                           (state/select! {:kind :rule
                                           :name (:name r)
                                           :domain domain-id}))})}])

      ;; Queries
      (when (seq queries)
        [{:key     (str "queries:" (name domain-id))
          :icon    (r/as-element [antd/QuestionCircleOutlined])
          :label   (str "Queries (" (count queries) ")")
          :children
          (for [q queries]
            {:key   (str "query:" (name domain-id) ":" (:name q))
             :label [:span.nav-label [:span "? " (:name q)]]
             :onClick #(do (state/switch-domain! domain-id)
                           (state/select! {:kind :query
                                           :name (:name q)
                                           :domain domain-id}))})}])

      ;; Sources
      (when (seq sources)
        [{:key     (str "sources:" (name domain-id))
          :icon    (r/as-element [antd/DatabaseOutlined])
          :label   (str "Sources (" (count sources) ")")
          :children
          (for [s sources]
            {:key   (str "source:" (name domain-id) ":" (:name s))
             :label [:span.nav-label
                     [:span "↺ " (:name s)]]
             :onClick #(do (state/switch-domain! domain-id)
                           (state/select! {:kind :source
                                           :name (:name s)
                                           :domain domain-id}))})}])

      ;; Notes
      (when (seq notes)
        [{:key     (str "notes:" (name domain-id))
          :icon    (r/as-element [antd/EditOutlined])
          :label   (str "Notes (" (count notes) ")")
          :children
          (for [n notes]
            {:key   (str "note:" (name domain-id) ":" (name n))
             :label [:span.nav-label [:span "✎ " (fmt-val n)]]
             :onClick #(do (state/switch-domain! domain-id)
                           (state/select! {:kind :entity
                                           :name n
                                           :domain domain-id}))})}]))))

(defn- domain-tree-order
  "DFS through domains-list, yielding [domain depth] pairs."
  [domains]
  (let [by-parent (group-by :parent domains)]
    (letfn [(walk [d depth]
              (cons [d depth]
                    (mapcat #(walk % (inc depth))
                            (sort-by :label (get by-parent (:id d) [])))))]
      (mapcat #(walk % 0) (sort-by :label (get by-parent nil []))))))

(defn- build-menu-items
  "Build the complete antd Menu items array."
  [state]
  (let [domains (state/domains-list)
        tree    (domain-tree-order domains)]
    (vec
     (concat
      ;; Home
      [{:key  "home"
        :icon (r/as-element [antd/HomeOutlined])
        :label "Home"
        :onClick #(state/go-home!)}]

      ;; Domain groups
      (for [[{:keys [id label]} depth] tree
            :let [exp? (contains? (or (:expanded state) #{}) id)]]
        {:key      (str "domain:" (name id))
         :icon     (r/as-element [antd/FolderOutlined])
         :label    [:span.domain-label
                    [:span label]
                    [:span.domain-menu-btn
                     {:on-click (fn [e]
                                  (.stopPropagation e)
                                  (open-popover-from-event!
                                   {:kind :domain-menu :domain id} e))}
                     "⋯"]]
         :children (domain-menu-items id state)})

      ;; Divider + settings
      [{:type "divider"}
       {:key  "settings"
        :icon (r/as-element [antd/SettingOutlined])
        :label "Settings"
        :onClick #(state/select! {:kind :settings})}]))))

(defn- sidebar-header []
  [:div.sidebar-header
   [:div.brand-row
    [:div.brand-logo "G"]
    [:span.brand-name "Golova"]
    [:span.brand-spacer]
    [:button.theme-btn
     {:title "Toggle theme"
      :on-click #(state/set-theme! (if (= :dark (:theme @app-state)) :light :dark))}
     (if (= :dark (:theme @app-state)) "☾" "☼")]]])



(defn sidebar []
  [:aside.sidebar "DEBUG: minimal sidebar"])

(defn sidebar-old []
  (let [{:keys [selection sidebar-mobile-open?]} @app-state
        state @app-state
        items (build-menu-items state)
        ;; Find the selected key based on current selection
        selected-key (case (:kind selection)
                       :home "home"
                       :settings "settings"
                       :rules (str "rules:" (name (or (:domain selection)
                                                       (:current-domain state))))
                       :type (str "type:" (name (or (:domain selection)
                                                     (:current-domain state)))
                                  ":" (:name selection))
                       :predicate (str "pred:" (name (or (:domain selection)
                                                          (:current-domain state)))
                                       ":" (:name selection) "/" (:arity selection))
                       :rule (str "rule:" (name (or (:domain selection)
                                                     (:current-domain state)))
                                  ":" (:name selection) "/" (or (:arity selection) ""))
                       :query (str "query:" (name (or (:domain selection)
                                                       (:current-domain state)))
                                   ":" (:name selection))
                       :source (str "source:" (name (or (:domain selection)
                                                         (:current-domain state)))
                                    ":" (:name selection))
                       :entity (str "note:" (name (or (:domain selection)
                                                       (:current-domain state)))
                                    ":" (name (:name selection)))
                       "")]
    [:aside.app-sidebar
     {:class (when sidebar-mobile-open? "mobile-open")}
     [sidebar-header]
     [:div.sidebar-nav "DEBUG: menu omitted"]
     [:div.sidebar-footer
      [:div.footer-shortcut
       [:span "Palette"]
       [:kbd "⌘K"]]
      [:div.footer-shortcut
       [:span "Rebuild"]
       [:kbd "⌘↵"]]]])))
