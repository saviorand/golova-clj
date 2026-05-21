(ns golova.state.csv
  "CSV import pipeline. The CSV parser itself lives in golova.csv —
  this layer turns a parsed CSV into predicate declarations + assert
  events, all filed into a target domain."
  (:require [clojure.string :as str]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.rebuild :as rebuild]))

(defn- col-type [values]
  (let [non-empty (remove str/blank? values)]
    (if (and (seq non-empty)
             (every? #(re-matches #"-?\d+" (str/trim %)) non-empty))
      "int" "string")))

(defn- transpose-cols [headers rows]
  (mapv (fn [i] (mapv #(nth % i "") rows)) (range (count headers))))

(defn csv-preview [{:keys [headers rows]}]
  (let [cols-vals (transpose-cols headers rows)]
    {:row-count (count rows)
     :col-count (count headers)
     :columns (mapv (fn [name vals]
                      {:name name :slug (core/slugify name) :type (col-type vals)
                       :non-empty (count (remove str/blank? vals))})
                    headers cols-vals)}))

(defn import-csv!
  "Import a parsed CSV into `domain-id`. id-idx is the column to use as
  entity ident. Each non-empty cell → triple; each non-id column →
  declared predicate `[atom <inferred>]`. Atoms get :in-domain → domain-id."
  [domain-id parsed id-idx]
  (let [{:keys [headers rows]} parsed
        cols-vals (transpose-cols headers rows)
        cols (mapv (fn [name vals]
                     {:name name :slug (core/slugify name) :type (col-type vals)})
                   headers cols-vals)
        new-preds (vec (for [[i c] (map-indexed vector cols)
                             :when (not= i id-idx)]
                         {:name (:slug c) :argTypes ["atom" (:type c)]
                          :domain domain-id}))
        entity-keys (vec (distinct
                           (for [row rows
                                 :let [s (core/slugify (nth row id-idx ""))]
                                 :when (seq s)]
                             (keyword s))))
        membership-evts (vec (for [k entity-keys]
                               (core/mk-event :assert
                                              {:triple [k :in-domain domain-id]}
                                              "csv-import")))
        fact-evts (vec
                    (for [row rows
                          :let [id-raw (nth row id-idx "")
                                id-slug (core/slugify id-raw)]
                          :when (seq id-slug)
                          [i v] (map-indexed vector row)
                          :when (and (not= i id-idx) (not (str/blank? v)))
                          :let [col (nth cols i)
                                attr (keyword (:slug col))
                                val (if (= "int" (:type col))
                                      (js/parseInt (str/trim v) 10) v)]]
                      (core/mk-event :assert
                                     {:triple [(keyword id-slug) attr val]}
                                     "csv-import")))
        skipped (count (filter #(str/blank? (core/slugify (nth % id-idx ""))) rows))]
    (swap! app-state update-in [:schema :predicates]
           (fn [ps]
             (let [existing (set (map :name ps))]
               (into (vec ps) (remove #(existing (:name %)) new-preds)))))
    (rebuild/append-events! (into membership-evts fact-evts))
    {:rows (count rows)
     :triples (count fact-evts)
     :predicates (count new-preds)
     :skipped-rows skipped}))
