(ns golova.ui.views.type
  "Type page: chip-editor for constructors, navigable list, move/delete."
  (:require [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.typed :refer [chip-editor]]
            [golova.ui.popover :refer [move-to-pill]]))

(defn type-view [name]
  (let [ctors-atom (r/atom [])
        loaded-name (r/atom nil)
        last-len (r/atom 0)]
    (fn [name]
      (let [t (first (filter #(= name (:name %))
                             (get-in @app-state [:schema :types])))
            domain-id (:domain t)
            schema-ctors (vec (:constructors t))]
        (when (or (not= name @loaded-name)
                  (not= (count schema-ctors) @last-len))
          (reset! ctors-atom schema-ctors)
          (reset! loaded-name name)
          (reset! last-len (count schema-ctors)))
        (if-not t
          [:div.view [:div.empty [:h3 "Type not found"]]]
          [:div.view
           [:div.view-head
            [:h2.mono name]
            [:span.desc (count schema-ctors) " value"
             (when (not= 1 (count schema-ctors)) "s")]
            [:span.pill.declared "type"]
            [:span.pill.schema-badge "ref · many"]
            [move-to-pill domain-id
             (fn [_src dst] (state/move-type! name dst))]
            [:div.actions-right
             [:button.primary.small
              {:disabled (= @ctors-atom schema-ctors)
               :on-click #(do (state/declare-type! domain-id name @ctors-atom)
                              (reset! last-len (count @ctors-atom)))}
              "Save changes"]
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm (str "Delete type " name "?"))
                             (state/delete-type! name)
                             (state/select! {:kind :rules})))}
              "Delete type"]]]
           [:div.type-editor
            [chip-editor
             {:state-atom ctors-atom
              :placeholder "type a name, Enter to add"
              :suggestions-fn #(state/untyped-atoms domain-id)}]
            [:div.hint
             "Click a chip's × to remove; suggestions are atoms in this "
             "domain that aren't typed yet. Hit "
             [:b "Save changes"] " to commit."]]
           (when (seq schema-ctors)
             [:div.constructor-list
              [:h3.section-h "Constructors"]
              (for [c schema-ctors]
                ^{:key c}
                [:div.nav-item {:style {:padding-left 0}}
                 [:span.icon "◇"]
                 [:span.name [:a.atom-link
                              {:on-click #(state/select! {:kind :entity
                                                          :name (keyword c)})}
                              c]]])])])))))
