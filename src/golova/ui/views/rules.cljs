(ns golova.ui.views.rules
  "'Rules / facts' page — the domain-overview view. Program editor on top
  (edits the current domain's rules), filterable + sortable stored-facts
  table below (scoped to facts about atoms in this domain)."
  (:require [clojure.string :as str]
            [cljs.pprint]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link pred-link]]
            [golova.ui.table :refer [table-toolbar sort-key sort-indicator
                                     cycle-sort row-matches?
                                     provenance-tooltips]]
            [golova.ui.typed :refer [constructor-type-map type-value]]))

(defn- rules->text
  "Pretty-print rule clauses as EDN, one per blank-separated block."
  [rules]
  (str/join "\n\n"
            (for [r (or rules [])]
              (with-out-str (cljs.pprint/pprint r)))))

(defn- text->rules
  "Parse the editor's text back into a rule vector. Wrap in [ ] and read
  as EDN so the user can write rules separated by whitespace."
  [text]
  (let [wrapped (str "[" text "]")]
    (vec (reader/read-string wrapped))))

(defn- domain-rule-clauses [domain-id]
  (mapv :clause (state/rules-in domain-id)))

(defn rules-view []
  (let [{:keys [current-domain]} @app-state
        initial (rules->text (domain-rule-clauses current-domain))
        local (r/atom {:text initial :loaded current-domain
                       :saved? true :err nil})
        table-state (r/atom {:query "" :provs #{}
                             :sort {:col-cur nil :dir nil}})]
    (fn []
      (let [domain-id (state/current-id)
            d-info (state/domain-info domain-id)
            current-text (rules->text (domain-rule-clauses domain-id))
            err (:build-error @app-state)]
        (when (not= domain-id (:loaded @local))
          (reset! local {:text current-text :loaded domain-id
                         :saved? true :err nil}))
        [:div.view
         [:div.view-head
          [:h2 "Rules"]
          [:span.desc "Datalog rules for "
           [:b (:label d-info)] ". Each rule is "
           [:code "[(head ?a ?b) body…]"]
           ". Edit and rebuild."]
          [:div.actions-right
           [:button.primary
            {:on-click (fn []
                         (try
                           (let [parsed (text->rules (:text @local))]
                             (state/set-rules! domain-id parsed)
                             (swap! local assoc :saved? true :err nil))
                           (catch :default e
                             (swap! local assoc :err (.-message e)))))}
            "Save rules"]]]
         [:div.program-editor {:class (cond (or err (:err @local)) "err"
                                            (not (:saved? @local)) "dirty")}
          [:textarea
           {:spellCheck "false"
            :value (:text @local)
            :on-change #(swap! local assoc :text (.. % -target -value)
                               :saved? false :err nil)
            :on-key-down (fn [e]
                           (when (and (or (.-metaKey e) (.-ctrlKey e))
                                      (= "Enter" (.-key e)))
                             (.preventDefault e)
                             (try
                               (let [parsed (text->rules (:text @local))]
                                 (state/set-rules! domain-id parsed)
                                 (swap! local assoc :saved? true :err nil))
                               (catch :default ex
                                 (swap! local assoc :err (.-message ex))))))}]
          [:div.footer
           [:span.status {:class (cond (or err (:err @local)) "err"
                                       (:saved? @local) "ok"
                                       :else "dirty")}
            (cond (or err (:err @local)) (str "error: " (or err (:err @local)))
                  (:saved? @local) "saved"
                  :else "edited — press Save (or ⌘↵)")]]]
         (when (:db @app-state)
           (let [ctor-map (constructor-type-map)
                 triples (state/triples-in-domain domain-id)
                 rejections (:rejections @app-state)
                 rows (mapv (fn [[e a v :as tr]]
                              {:e e :a a :v v :tr (vec tr)
                               :prov (state/triple-provenance tr)})
                            triples)
                 provs-present (set (map :prov rows))
                 {:keys [query provs sort]} @table-state
                 filtered (filterv #(row-matches? query [(:e %) (:a %) (:v %)]) rows)
                 filtered (if (seq provs)
                            (filterv #(contains? provs (:prov %)) filtered)
                            filtered)
                 filtered (let [{:keys [col-cur dir]} sort]
                            (if (and col-cur dir)
                              (let [k (case col-cur 0 :e 1 :a 2 :v 3 :prov nil)]
                                (if k
                                  (vec ((if (= dir :desc) reverse identity)
                                        (sort-by (comp sort-key k) filtered)))
                                  filtered))
                              filtered))
                 capped (take 200 filtered)
                 sort-cur (:col-cur sort)
                 sort-dir (:dir sort)
                 header (fn [i label]
                          [:th.sortable {:on-click #(cycle-sort table-state i)}
                           label
                           [:span.sort-arrow
                            (when (= i sort-cur) (sort-indicator sort-dir))]])]
             [:div {:style {:margin-top "24px"}}
              [:h3.materialized-h
               "Stored facts"
               [:span.count-hint (count rows) " total"
                (when (> (count filtered) 200) (str " · showing first 200 of "
                                                    (count filtered)))]
               (when (seq rejections)
                 [:span.rejection-count
                  (str " · " (count rejections) " rejected by schema")])]
              (when (seq rejections)
                [:div.rejection-warnings
                 (for [[i r] (map-indexed vector (take 5 rejections))]
                   ^{:key i}
                   [:div.rej-item
                    [:span.rej-attr (str (or (:attribute r) ""))]
                    [:span.rej-msg (:message r)]])
                 (when (> (count rejections) 5)
                   [:div.rej-more (str "+ " (- (count rejections) 5) " more")])])
              [table-toolbar
               {:state-atom table-state
                :provs-present provs-present
                :total (count rows)
                :shown (count filtered)}]
              [:table.facts
               [:thead [:tr
                        [header 0 "subject"]
                        [header 1 "predicate"]
                        [header 2 "object"]
                        [header 3 "source"]
                        [:th ""]]]
               [:tbody
                (doall
                 (for [{:keys [e a v tr prov]} capped]
                   (let [pred-si (state/attr-schema-info a)
                         pred-title (if pred-si
                                      (str (name a) " — "
                                           (state/db-type-label (:db/valueType pred-si))
                                           " · "
                                           (if (= :db.cardinality/many
                                                  (:db/cardinality pred-si))
                                             "many" "one"))
                                      (str (name a) " — no schema entry (discovered)"))]
                     ^{:key (pr-str tr)}
                     [:tr
                      [:td [:span {:title "entity (atom — :db/ident keyword)"}
                            (atom-link e)]]
                      [:td [:span {:title pred-title} [pred-link a]]]
                      [:td [type-value ctor-map v]]
                      [:td [:span.pill {:class (str "prov-" (name prov))
                                        :title (provenance-tooltips prov)}
                            (name prov)]]
                      [:td.delete
                       (when (= :event prov)
                         [:button.ghost.danger
                          {:title "Retract this fact"
                           :on-click #(state/retract-triple! (vec tr))}
                          "×"])]])))]]]))]))))
