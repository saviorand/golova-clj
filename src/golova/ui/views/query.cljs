(ns golova.ui.views.query
  "Saved query page: textarea + Run/Save, inline solutions, pin/unpin,
  move-to-domain, delete."
  (:require [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link]]
            [golova.ui.popover :refer [move-to-pill]]))

(def ^:private result-row-cap 200)

(defn query-view [name]
  (let [q (first (filter #(= name (:name %))
                         (get-in @app-state [:schema :queries])))
        local (r/atom {:text (:text q) :loaded-name name :result nil})]
    (fn [name]
      (let [q (first (filter #(= name (:name %))
                             (get-in @app-state [:schema :queries])))
            domain-id (:domain q)]
        (when (not= name (:loaded-name @local))
          (reset! local {:text (:text q) :loaded-name name :result nil}))
        (if-not q
          [:div.view [:div.empty [:h3 "Query not found"]]]
          [:div.view
           [:div.view-head
            [:h2.mono (:name q)]
            [:span.pill "saved query"]
            (when (:pinned? q)
              [:span.pill.pinned {:title "Pinned to Home"} "pinned"])
            [move-to-pill domain-id
             (fn [_src dst] (state/move-query! (:name q) dst))]
            [:div.actions-right
             [:button.ghost.small
              {:title (if (:pinned? q)
                        "Unpin from Home"
                        "Pin to Home — show inline results on the Home page")
               :on-click #(state/toggle-pin-query! (:name q))}
              (if (:pinned? q) "Unpin" "Pin to Home")]
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm (str "Delete saved query " (:name q) "?"))
                             (state/delete-query! (:name q))
                             (state/select! {:kind :rules})))} "Delete"]]]
           [:div.program-editor
            [:textarea {:value (:text @local)
                        :on-change #(swap! local assoc :text (.. % -target -value))
                        :spellCheck "false"
                        :rows 3}]
            [:div.footer
             [:button.primary
              {:on-click (fn []
                           (let [r (state/run-query (:text @local))]
                             (swap! local assoc :result r)))}
              "Run"]
             [:button
              {:on-click (fn []
                           (state/save-query! domain-id (:name q) (:text @local)))}
              "Save"]]]
           (when-let [r (:result @local)]
             [:div {:style {:margin-top "16px"}}
              (cond
                (:error r) [:div.results.err (:error r)]
                (empty? (:rows r)) [:div.results [:div.summary "no solutions"]]
                :else
                (let [rows  (:rows r)
                      total (count rows)
                      shown (take result-row-cap rows)]
                  [:div.results
                   [:div.summary total " solution" (when (not= 1 total) "s")
                    (when (> total result-row-cap)
                      [:span.trunc " · showing first " result-row-cap])]
                   (for [[i row] (map-indexed vector shown)]
                     ^{:key i}
                     [:div.row
                      (for [[k v] (map vector (:vars r) row)]
                        ^{:key k}
                        [:span.b [:span.k k] " = " [:span.v (atom-link v)]])])]))])])))))
