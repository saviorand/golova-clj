(ns golova.ui.views.predicate
  "Predicate page: declared/discovered badge, schema info, sortable +
  filterable facts table, inline edit + add rows, 'Defined by' /
  'Used in rules' cross-references."
  (:require [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [fmt-val]]
            [golova.ui.table :refer [table-toolbar table-pagination page-window
                                     sort-key sort-indicator
                                     cycle-sort row-matches?]]
            [golova.ui.typed :refer [constructor-type-map type-value typed-input]]
            [golova.ui.derivations :refer [rule-clause rules-defining-attr
                                            rules-using-attr]]
            [golova.ui.popover :refer [move-to-pill]]))

(defn- type-icon-for [t]
  (case t "int" "#" "string" "\"" "atom" "◇" "◆"))

(defn- guess-type
  "Heuristic arg type for an undeclared 2-arity predicate, picked from
  the current row's value. Falls back to \"atom\"."
  [v]
  (cond
    (integer? v) "int"
    (number?  v) "int"
    (string?  v) "string"
    (keyword? v) "atom"
    :else        "atom"))

(defn- pred-edit-row
  "Editable display row for one triple in the predicate table."
  [arg-types triple]
  (let [tr (vec triple)
        [e a v] tr
        edit (r/atom nil)
        ctor-map (constructor-type-map)]
    (fn [arg-types triple]
      (let [tr (vec triple)
            [e a v] tr
            t1 (or (first arg-types)  (guess-type e))
            t2 (or (second arg-types) (guess-type v))
            cell (fn [idx orig arg-type]
                   (if (and @edit (= idx (:idx @edit)))
                     [:td.editing
                      [typed-input
                       {:arg-type  arg-type
                        :state     (r/cursor edit [:st])
                        :autofocus? true
                        :on-enter (fn []
                                    (try
                                      (let [coerced (state/coerce-value
                                                      arg-type
                                                      (:val (:st @edit)))
                                            new-tr (assoc tr idx coerced)]
                                        (state/replace-triple! tr new-tr)
                                        (reset! edit nil))
                                      (catch :default ex
                                        (swap! edit assoc :err (.-message ex)))))}]
                      [:button.ghost {:on-click #(reset! edit nil)
                                       :title "Cancel (Esc)"} "✕"]]
                     [:td.cellv
                      {:on-click #(reset! edit
                                          {:idx idx
                                           :st {:val (cond
                                                       (keyword? orig) (fmt-val orig)
                                                       (string? orig) orig
                                                       :else (str orig))}})
                       :title "Click to edit"}
                      [type-value ctor-map orig]]))]
        [:tr
         (cell 0 e t1)
         (cell 2 v t2)
         [:td.delete
          [:button.ghost.danger
           {:title "Retract"
            :on-click #(state/retract-triple! tr)}
           "×"]]]))))

(defn- pred-add-row
  "<tfoot> add-row form: typed inputs + Add. owning-domain auto-assigns
  :in-domain on new subjects."
  [owning-domain pred-name arg-types]
  (let [n (count arg-types)
        cells (vec (repeatedly n #(r/atom {:val ""})))
        err (r/atom nil)]
    (fn [_ _ arg-types]
      [:tr
       (for [[i t] (map-indexed vector arg-types)]
         ^{:key i}
         [:td
          [typed-input
           {:arg-type  t
            :state     (get cells i)
            :placeholder t}]])
       [:td.delete
        [:button.primary.add-fact
         {:title "Add fact (Enter)"
          :on-click (fn []
                      (try
                        (state/assert-from-form!
                          pred-name arg-types
                          (mapv #(:val @%) cells)
                          owning-domain)
                        (doseq [c cells] (reset! c {:val ""}))
                        (reset! err nil)
                        (catch :default ex
                          (reset! err (.-message ex)))))}
         "+ Add"]
        (when @err [:div.err.mini @err])]])))

(defn predicate-view [name arity]
  (let [ui-state (r/atom {:query "" :provs #{} :page 0 :page-size 200
                          :sort {:col-cur nil :dir nil}})]
    (fn [name arity]
      (let [domain-id (state/current-id)
            attr (keyword name)
            all-triples (filter #(= attr (second %)) (state/all-triples))
            declared (first (filter #(and (= name (:name %))
                                          (= arity (count (:argTypes %))))
                                    (get-in @app-state [:schema :predicates])))
            arg-types (when declared (:argTypes declared))
            bare-rules (mapv :clause (:rules @app-state))
            defining-rules (rules-defining-attr bare-rules attr)
            using-rules (rules-using-attr bare-rules attr)
            rows (mapv (fn [[e a v :as tr]]
                         {:e e :v v :tr (vec tr)
                          :prov (state/triple-provenance tr)})
                       all-triples)
            provs-present (set (map :prov rows))
            {:keys [query provs sort]} @ui-state
            filtered (filterv #(row-matches? query [(:e %) (:v %)]) rows)
            filtered (if (seq provs)
                       (filterv #(contains? provs (:prov %)) filtered)
                       filtered)
            filtered (let [{:keys [col-cur dir]} sort]
                       (if (and col-cur dir)
                         (let [k (case col-cur 0 :e 1 :v 2 :prov nil)]
                           (if k
                             (vec ((if (= dir :desc) reverse identity)
                                   (sort-by (comp sort-key k) filtered)))
                             filtered))
                         filtered))
            {:keys [page-size start]} (page-window ui-state (count filtered))
            paged (->> filtered (drop start) (take page-size))
            sort-cur (:col-cur sort)
            sort-dir (:dir sort)]
        [:div.view
         [:div.view-head
          [:h2.mono (str name "/" arity)]
          [:span.desc (count all-triples) " fact"
           (when (not= 1 (count all-triples)) "s")]
          (if declared
            [:span.pill.declared
             {:title "This predicate's arg types are declared in the domain's schema."}
             "declared"]
            [:span.pill
             {:title (str "This attribute exists in stored facts but has no declared "
                          "arg types. Click 'Declare types' below to add a schema entry.")}
             "discovered"])
          (let [si (state/attr-schema-info attr)]
            (when si
              (let [vt (:db/valueType si)
                    card (:db/cardinality si)
                    vt-label (state/db-type-label vt)
                    many? (= :db.cardinality/many card)]
                [:span.pill.schema-badge
                 {:title (str "Datahike value type: " vt-label
                              " (" (clojure.core/name vt) "). Cardinality: "
                              (if many?
                                "many — a single entity may hold many values for this attribute."
                                "one — only one value per entity (overwrites on re-assert)."))}
                 (str vt-label " · " (if many? "many" "one"))])))
          [move-to-pill (:domain declared)
           (fn [_src dst] (state/move-predicate! name arity dst))]
          [:div.actions-right
           (when declared
             [:button.ghost.small
              {:title "Convert this predicate's values into refs (atoms in a target domain)"
               :on-click #(state/open-modal! {:kind :change-pred-type
                                              :pred-name name
                                              :pred-arity arity})}
              "Change type"])
           (when declared
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm
                                  (str "Delete predicate declaration " name "/" arity
                                       "?\nFacts are not deleted."))
                             (state/delete-predicate! name arity)
                             (state/select! {:kind :rules})))}
              "Delete declaration"])]]
         [table-toolbar
          {:state-atom ui-state
           :provs-present provs-present
           :total (count rows)
           :shown (count filtered)}]
         [table-pagination
          {:state-atom ui-state
           :total (count filtered)
           :page-size 200}]
         [:table.facts
          [:thead
           [:tr
            (if declared
              (for [[i t] (map-indexed vector arg-types)]
                ^{:key i}
                [:th.sortable {:on-click #(cycle-sort ui-state i)}
                 [:div.h
                  [:span.type-icon {:title t} (type-icon-for t)]
                  [:span t]
                  [:span.sort-arrow (when (= i sort-cur) (sort-indicator sort-dir))]]])
              [:<> [:th.sortable {:on-click #(cycle-sort ui-state 0)}
                    [:div.h [:span.type-icon "◇"] [:span "subject"]
                     [:span.sort-arrow (when (= 0 sort-cur) (sort-indicator sort-dir))]]]
                   [:th.sortable {:on-click #(cycle-sort ui-state 1)}
                    [:div.h [:span.type-icon "◇"] [:span "object"]
                     [:span.sort-arrow (when (= 1 sort-cur) (sort-indicator sort-dir))]]]])
            [:th ""]]]
          [:tbody
           (if (empty? filtered)
             [:tr [:td {:col-span (inc (or (count arg-types) 2))
                        :style {:padding "24px" :text-align "center"
                                :color "var(--muted)"
                                :font-family "var(--sans-font)"}}
                   (if (empty? all-triples)
                     "No facts yet — use the row below to add one."
                     "No rows match the current filter.")]]
             (for [{:keys [tr]} paged]
               ^{:key (pr-str tr)}
               [pred-edit-row (or arg-types []) tr]))]
          (let [effective-types (or arg-types ["atom" "atom"])]
            (when (= 2 (count effective-types))
              [:tfoot
               [pred-add-row domain-id name effective-types]]))]
         (when-not declared
           [:div.declare-hint
            "This predicate isn't declared. "
            [:a.atom-link
             {:on-click #(state/open-modal! {:kind :new-predicate
                                              :preset-name name
                                              :domain domain-id})}
             "Declare types"] " to get a typed table with dropdowns."])
         (when (and declared (not= 2 (count arg-types)))
           [:div.declare-hint
            "Add-row form only supports arity-2 predicates today. "
            [:span {:style {:color "var(--dim)"}}
             "(Arity " (count arg-types) " requires entity reification.)"]])
         (when (seq defining-rules)
           [:div.used-in
            [:h3.section-h "Defined by"]
            [:div.section-hint
             "These rules produce facts under "
             [:code (str ":" name)] " when the engine materialises derivations."]
            (for [[i r] (map-indexed vector defining-rules)
                  :let [head (first r)
                        head-name (clojure.core/name (first head))
                        head-arity (count (rest head))]]
              ^{:key i}
              [:div.used-in-card
               [:div.used-in-head
                [:a.atom-link
                 {:on-click #(state/select! {:kind :rule :name head-name})}
                 head-name "/" head-arity]]
               [:div.rule-card.compact
                [rule-clause r]]])])
         (when (seq using-rules)
           [:div.used-in
            [:h3.section-h "Used in rules"]
            [:div.section-hint
             "These rules reference "
             [:code (str ":" name)] " in their body — they're consumers."]
            (for [[i r] (map-indexed vector using-rules)
                  :let [head (first r)
                        head-name (when (and (seq? head) (symbol? (first head)))
                                    (clojure.core/name (first head)))
                        head-arity (when (seq? head) (count (rest head)))]]
              ^{:key i}
              [:div.used-in-card
               (when head-name
                 [:div.used-in-head
                  [:a.atom-link
                   {:on-click #(state/select! {:kind :rule :name head-name})}
                   head-name "/" head-arity]])
               [:div.rule-card.compact
                [rule-clause r]]])])]))))
