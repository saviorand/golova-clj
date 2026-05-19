(ns golova.ui.table
  "Table-rendering helpers: toolbar (search + provenance filter), typed
  sort key, sort indicator + cycle, row-matches predicate."
  (:require [clojure.string :as str]))

(def provenance-colors
  {:event    "prov-event"
   :derived  "prov-derived"
   :program  "prov-program"
   :imported "prov-imported"})

(def provenance-tooltips
  {:event    "Asserted via the UI or imported snapshot. Lives in the event log; can be retracted."
   :derived  "Materialised by a rule from other facts. Cannot be retracted directly — change the rule or its inputs."
   :program  "Came from the program text (set-rules! / scratch editor)."
   :imported "Imported from another domain via cross-domain imports."})

(defn row-matches?
  "Substring case-insensitive match across stringified row cells."
  [query row]
  (or (str/blank? query)
      (let [q (str/lower-case query)
            stringy (str/lower-case
                     (str/join " "
                               (map (fn [v]
                                      (cond
                                        (keyword? v) (str (when (namespace v)
                                                           (str (namespace v) "/"))
                                                          (name v))
                                        (nil? v) ""
                                        :else (str v)))
                                    row)))]
        (str/includes? stringy q))))

(defn sort-key
  "Comparable sort key for a cell value. Numbers compare numerically;
  strings and keywords sort within their own bucket; nil sorts last."
  [v]
  (cond
    (nil? v)     [9 ""]
    (number? v)  [0 v]
    (boolean? v) [1 (if v 1 0)]
    (keyword? v) [2 (str (namespace v) "/" (name v))]
    (string? v)  [3 v]
    :else        [3 (str v)]))

(defn table-toolbar
  "Renders a search input, provenance filter pills, and a row-count summary.
  `state-atom` holds {:query string :provs #{kw}}."
  [{:keys [state-atom provs-present total shown]}]
  (let [st @state-atom]
    [:div.table-toolbar
     [:input.tt-search
      {:type "text"
       :placeholder "Search rows…"
       :value (:query st)
       :on-change #(swap! state-atom assoc :query (.. % -target -value))}]
     (when (seq provs-present)
       [:div.tt-provs
        (for [p [:event :derived :program :imported]
              :when (provs-present p)]
          ^{:key p}
          [:button.prov-toggle
           {:class (str (provenance-colors p)
                        (when (contains? (:provs st) p) " on"))
            :title (str "Filter to " (name p) " facts only. "
                        (provenance-tooltips p))
            :on-click #(swap! state-atom update :provs
                              (fn [s] (let [s (or s #{})]
                                        (if (contains? s p) (disj s p) (conj s p)))))}
           (name p)])
        (when (seq (:provs st))
          [:button.prov-clear {:on-click #(swap! state-atom assoc :provs #{})}
           "clear"])])
     [:div.tt-summary
      shown " of " total
      (when (and (= shown 0) (pos? total))
        " · no matches")]]))

(defn sort-indicator [dir]
  (case dir :asc " ▲" :desc " ▼" ""))

(defn cycle-sort
  "Cycle through nil → :asc → :desc → nil for a given column."
  [ui-state col]
  (swap! ui-state update :sort
         (fn [{:keys [col-cur dir]}]
           (cond
             (not= col col-cur) {:col-cur col :dir :asc}
             (= dir :asc)       {:col-cur col :dir :desc}
             (= dir :desc)      {:col-cur nil :dir nil}
             :else              {:col-cur col :dir :asc}))))
