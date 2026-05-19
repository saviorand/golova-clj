(ns golova.ui.views.domain
  "Domain detail page — editable label, editable parent (with cycle
  protection), and lists of members, subdomains, declared schema."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link]]))

(defn- parent-picker
  "Inline dropdown of candidate parents — every other domain that this
  one is not an ancestor of (so picking it doesn't create a cycle).
  Selecting a value commits via `move-domain-parent!`."
  [id current-parent]
  (let [doms (state/domains-list)
        candidates (remove (fn [d] (or (= (:id d) id)
                                       (state/ancestor-of? id (:id d))))
                           doms)]
    [:select.typed
     {:value (or (some-> current-parent name) "__none__")
      :on-change (fn [e]
                   (let [v (.. e -target -value)
                         tgt (when (not= "__none__" v) (keyword v))]
                     (try (state/move-domain-parent! id tgt)
                          (catch :default ex
                            (js/alert (str "Couldn't move: " (.-message ex)))))))}
     [:option {:value "__none__"} "— top-level (no parent) —"]
     (for [{:keys [id label]} candidates]
       ^{:key id} [:option {:value (name id)} label])]))

(defn domain-view [id]
  (let [rename-state (r/atom nil)]
    (fn [id]
      (let [info (state/domain-info id)]
        (if-not info
          [:div.view [:div.empty [:h3 "Domain not found"]]]
          (let [{:keys [label parent]} info
                parent-label (some-> parent state/domain-info :label)
                members (state/atoms-in-domain id)
                subs (state/subdomains-of id)
                types (state/declared-types-in id)
                preds (state/declared-predicates-in id)
                queries (state/queries-in id)
                rules (state/rules-in id)]
            [:div.view
             [:div.view-head
              [:h2 label]
              [:span.pill.declared "domain"]
              (when parent-label
                [:span.pill
                 {:title "Parent domain"}
                 "in "
                 [:a.atom-link
                  {:on-click #(state/select! {:kind :domain :domain parent})}
                  parent-label]])
              [:div.actions-right
               [:button.ghost.small
                {:on-click #(state/open-modal! {:kind :rename-domain :domain id})}
                "Rename"]
               [:button.ghost.small
                {:on-click #(state/open-modal! {:kind :new-domain :parent id})}
                "+ Subdomain"]
               [:button.ghost.danger
                {:on-click (fn []
                             (when (js/confirm
                                    (str "Delete domain " label
                                         "?\nSubdomains will be detached "
                                         "(become top-level). "
                                         "Facts/schema attached to this "
                                         "domain will lose their :in-domain."))
                               (state/delete-domain! id)
                               (state/go-home!)))}
                "Delete"]]]

             [:div.section-card
              [:div.section-card-head [:h3 "Parent"]]
              [:div.section-card-body
               [parent-picker id parent]
               [:p.hint
                "Re-parent this domain. The picker hides options that "
                "would form a cycle (you can't move a domain under its "
                "own descendant)."]]]

             [:div.section-card
              [:div.section-card-head
               [:h3 "Subdomains "
                [:span.count (count subs)]
                [:button.ghost.small
                 {:on-click #(state/open-modal!
                              {:kind :new-domain :parent id})}
                 "+ Add"]]]
              [:div.section-card-body
               (if (empty? subs)
                 [:div.empty-state "No subdomains."]
                 [:div
                  (for [{:keys [id label]} subs]
                    ^{:key id}
                    [:div.nav-item {:on-click #(state/select!
                                                {:kind :domain :domain id})}
                     [:span.icon "□"] [:span.name label]])])]]

             [:div.section-card
              [:div.section-card-head
               [:h3 "Members "
                [:span.count (count members)]]]
              [:div.section-card-body
               (if (empty? members)
                 [:div.empty-state "No atoms in this domain yet."]
                 [:div.constructor-list
                  (for [m (take 100 members)]
                    ^{:key m}
                    [:div.nav-item {:style {:padding-left 0}}
                     [:span.icon "◇"] [:span.name (atom-link m)]])
                  (when (> (count members) 100)
                    [:div.hint (str "+ " (- (count members) 100) " more")])])]]

             [:div.section-card
              [:div.section-card-head [:h3 "Schema"]]
              [:div.section-card-body
               [:p.hint
                (count types) " type" (when (not= 1 (count types)) "s") ", "
                (count preds) " predicate" (when (not= 1 (count preds)) "s") ", "
                (count rules) " rule" (when (not= 1 (count rules)) "s") ", "
                (count queries) " saved quer"
                (if (= 1 (count queries)) "y" "ies") "."]
               [:button
                {:on-click #(state/select! {:kind :rules :domain id})}
                "Open Rules / facts →"]]]]))))))
