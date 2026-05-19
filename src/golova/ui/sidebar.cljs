(ns golova.ui.sidebar
  "Left rail: brand, Home link, domain tree (each domain → collapsible
  subsections for types/predicates/rules/queries/notes plus a Rules / facts
  overview leaf), footer with palette + settings buttons."
  (:require [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [fmt-val]]
            [golova.ui.derivations :refer [domain-predicates domain-rules]]
            [golova.ui.popover :refer [open-popover-from-event!]]))

(defn- sub
  "Collapsible header row for a subsection within an expanded domain block."
  [{:keys [label items add-fn expanded? on-toggle]}]
  (let [n (count items)]
    [:div.subsection {:class (str (when (zero? n) "empty ")
                                  (when expanded? "expanded"))
                      :on-click on-toggle}
     [:span.chev]
     [:span.lbl label]
     [:span.scount n]
     (when add-fn
       [:button.sub-add
        {:title (str "Add " label)
         :on-click (fn [e] (.stopPropagation e) (add-fn))} "+"])]))

(defn- nav-item [{:keys [active? icon icon-tooltip label meta on-click extra-class]}]
  [:div.nav-item {:class (str (when active? "active")
                              (when extra-class (str " " extra-class)))
                  :on-click on-click}
   (if icon-tooltip
     [:span.icon {:title icon-tooltip} icon]
     [:span.icon icon])
   [:span.name.mono label]
   (when meta [:span.meta meta])])

(defn- domain-tree-order
  "DFS through domains-list, yielding [domain depth] pairs in parent-then-
  children order. Used to render the sidebar as a tree."
  [domains]
  (let [by-parent (group-by :parent domains)]
    (letfn [(walk [d depth]
              (cons [d depth]
                    (mapcat #(walk % (inc depth))
                            (sort-by :label (get by-parent (:id d) [])))))]
      (mapcat #(walk % 0) (sort-by :label (get by-parent nil []))))))

(defn sidebar []
  (let [{:keys [current-domain selection expanded expanded-subs]} @app-state
        domains (state/domains-list)
        on-home? (= :home (:kind selection))
        sub-exp? (fn [id k] (contains? (or expanded-subs #{}) [id k]))]
    [:aside
     [:div.brand
      [:span.logo "G"]
      [:span "Golova"]
      [:span.spacer]
      [:button.theme-toggle
       {:title "Toggle theme"
        :on-click #(state/set-theme! (if (= :dark (:theme @app-state)) :light :dark))}
       (if (= :dark (:theme @app-state)) "☾" "☼")]]

     [:div.home-link {:class (when on-home? "active")
                      :on-click #(state/go-home!)}
      [:span.icon "🏠"]
      [:span.lbl "Home"]]

     [:div.section
      [:h3 "Domains "
       [:span.count (count domains)]
       [:button.ghost.add-mini
        {:title "New domain"
         :on-click #(state/open-modal! {:kind :new-domain})}
        "+"]]
      (doall
       (for [[{:keys [id label]} depth] (domain-tree-order domains)
             :let [exp? (contains? (or expanded #{}) id)
                   active-domain? (= id current-domain)
                   preds (domain-predicates id)
                   decl-pred (filter :declared? preds)
                   disc-pred (filter (complement :declared?) preds)
                   rules (domain-rules id)
                   types (state/declared-types-in id)
                   queries (state/queries-in id)]]
        ^{:key (str "d-" (name id))}
        [:div.domain-block {:class (when (pos? depth) (str "subdomain depth-" depth))
                            :style (when (pos? depth)
                                     {:padding-left (str (* depth 12) "px")})}
         [:div.domain-header
          {:class (when exp? "expanded")
           :on-click (fn []
                       (if (= id current-domain)
                         (state/toggle-domain! id)
                         (do (state/switch-domain! id)
                             (state/expand-domain! id))))}
          [:span.chev]
          [:span.name label]
          [:button.row-menu
           {:title "Domain menu"
            :on-click (fn [e]
                        (.stopPropagation e)
                        (open-popover-from-event!
                         {:kind :domain-menu :domain id} e))}
           "⋯"]]
         (when exp?
           (let [all-preds (concat decl-pred disc-pred)
                 notes (state/entities-with-notes id)]
             [:div.domain-body
              [nav-item
               {:active? (and active-domain?
                              (= :rules (:kind selection))
                              (= id (or (:domain selection) current-domain)))
                :icon "≡"
                :icon-tooltip "Domain overview: program editor + all stored facts"
                :label "Rules / facts"
                :extra-class "overview"
                :on-click #(do (state/switch-domain! id)
                               (state/select! {:kind :rules :domain id}))}]
              (sub {:label "Types" :items types
                    :expanded? (sub-exp? id :types)
                    :on-toggle #(state/toggle-subsection! id :types)
                    :add-fn (fn []
                              (state/expand-subsection! id :types)
                              (state/open-modal! {:kind :new-type :domain id}))})
              (when (sub-exp? id :types)
                (for [t types]
                  ^{:key (str "t-" (:name t))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :type (:kind selection))
                                  (= (:name t) (:name selection)))
                    :icon "◆"
                    :label (:name t)
                    :meta (count (:constructors t))
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :type
                                                   :name (:name t)
                                                   :domain id}))}]))
              (sub {:label "Predicates" :items all-preds
                    :expanded? (sub-exp? id :predicates)
                    :on-toggle #(state/toggle-subsection! id :predicates)
                    :add-fn (fn []
                              (state/expand-subsection! id :predicates)
                              (state/open-modal! {:kind :new-predicate :domain id}))})
              (when (sub-exp? id :predicates)
                (for [p all-preds]
                  ^{:key (str "p-" (:name p) "/" (:arity p))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :predicate (:kind selection))
                                  (= (:name p) (:name selection))
                                  (= (:arity p) (:arity selection)))
                    :icon (if (:declared? p) "▦" "▢")
                    :icon-tooltip (if (:declared? p)
                                    "Declared — arg types are in this domain's schema."
                                    "Discovered — facts exist but no declared arg types.")
                    :label (str (:name p) "/" (:arity p))
                    :meta (count (:facts p))
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :predicate
                                                   :name (:name p)
                                                   :arity (:arity p)
                                                   :domain id}))}]))
              (sub {:label "Rules" :items rules
                    :expanded? (sub-exp? id :rules)
                    :on-toggle #(state/toggle-subsection! id :rules)
                    :add-fn (fn []
                              (state/expand-subsection! id :rules)
                              (state/open-modal! {:kind :new-rule :domain id}))})
              (when (sub-exp? id :rules)
                (for [r rules]
                  ^{:key (str "r-" (:name r) "/" (:arity r))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :rule (:kind selection))
                                  (= (:name r) (:name selection)))
                    :icon "ƒ"
                    :label (str (:name r) "/" (:arity r))
                    :meta (when (> (count (:clauses r)) 1)
                            (count (:clauses r)))
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :rule
                                                   :name (:name r)
                                                   :domain id}))}]))
              (sub {:label "Queries" :items queries
                    :expanded? (sub-exp? id :queries)
                    :on-toggle #(state/toggle-subsection! id :queries)
                    :add-fn (fn []
                              (state/expand-subsection! id :queries)
                              (state/open-modal! {:kind :new-query :domain id}))})
              (when (sub-exp? id :queries)
                (for [q queries]
                  ^{:key (str "q-" (:name q))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :query (:kind selection))
                                  (= (:name q) (:name selection)))
                    :icon "?"
                    :label (:name q)
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :query
                                                   :name (:name q)
                                                   :domain id}))}]))
              (when (seq notes)
                [:<>
                 (sub {:label "Notes" :items notes
                       :expanded? (sub-exp? id :notes)
                       :on-toggle #(state/toggle-subsection! id :notes)})
                 (when (sub-exp? id :notes)
                   (for [n notes]
                     ^{:key (str "n-" (name n))}
                     [nav-item
                      {:active? (and active-domain?
                                     (= :entity (:kind selection))
                                     (= n (:name selection)))
                       :icon "✎"
                       :label (fmt-val n)
                       :on-click #(do (state/switch-domain! id)
                                      (state/select! {:kind :entity
                                                      :name n
                                                      :domain id}))}]))])]))]))]

     [:div.sidebar-footer
      [:div.foot-row
       [:button.ghost
        {:title "Command palette (⌘K)"
         :on-click #(state/open-palette!)}
        "⌘ Search / commands"]]
      [:div.foot-row
       [:button.ghost
        {:title "Settings"
         :on-click #(state/open-modal! {:kind :settings})}
        "⚙ Settings"]]
      [:div.kshort [:span.lbl "Palette"] [:kbd "⌘K"]]
      [:div.kshort [:span.lbl "Rebuild"] [:kbd "⌘↵"]]]]))
