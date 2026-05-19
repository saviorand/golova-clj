(ns golova.ui.views.rule
  "Single-rule page: pretty-printed clauses with a link back to the Rules
  editor."
  (:require [golova.state :as state]
            [golova.ui.derivations :refer [domain-rules rule-clause]]))

(defn rule-view [name]
  (let [domain-id (state/current-id)
        entry (first (filter #(= name (:name %)) (domain-rules domain-id)))
        clauses (or (:clauses entry) [])]
    [:div.view
     [:div.view-head
      [:h2.mono (str name "/" (:arity entry))]
      [:span.desc (count clauses) " clause"
       (when (not= 1 (count clauses)) "s")]
      [:span.pill.derived "derived"]
      [:div.actions-right
       [:button
        {:on-click #(state/select! {:kind :rules})}
        "Edit in Rules"]]]
     (for [[i r] (map-indexed vector clauses)]
       ^{:key i}
       [:div.rule-card
        [rule-clause r]])
     [:div.hint
      "Rules live in the domain's Rules editor. Edit there to change."]]))
