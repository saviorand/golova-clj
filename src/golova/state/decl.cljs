(ns golova.state.decl
  "Schema declarations (UI metadata). Each declaration carries a :domain
  field for sidebar placement. Also covers rule CRUD and the move-*
  operations that re-file a declaration into a different domain."
  (:require [clojure.string :as str]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.rebuild :as rebuild]))

;; ---------------------------------------------------------------------------
;; Types

(defn declare-type! [domain-id name constructors]
  (let [name (str name)
        ctors (vec (remove str/blank? constructors))]
    (swap! app-state update-in [:schema :types]
           (fn [ts]
             (let [without (vec (remove #(= name (:name %)) (or ts [])))]
               (conj without {:name name :constructors ctors :domain domain-id})))))
  (rebuild/rebuild!) (core/save!))

(defn delete-type! [name]
  (swap! app-state update-in [:schema :types]
         (fn [ts] (vec (remove #(= name (:name %)) (or ts [])))))
  (rebuild/rebuild!) (core/save!))

;; ---------------------------------------------------------------------------
;; Predicates

(defn declare-predicate! [domain-id name arg-types]
  (let [name (str name)
        arg-types (vec (remove str/blank? arg-types))
        arity (count arg-types)]
    (swap! app-state update-in [:schema :predicates]
           (fn [ps]
             (let [without (vec (remove #(and (= name (:name %))
                                              (= arity (count (:argTypes %))))
                                        (or ps [])))]
               (conj without {:name name :argTypes arg-types :domain domain-id})))))
  (rebuild/rebuild!) (core/save!))

(defn delete-predicate! [name arity]
  (swap! app-state update-in [:schema :predicates]
         (fn [ps] (vec (remove #(and (= name (:name %))
                                     (= arity (count (:argTypes %))))
                               (or ps [])))))
  (rebuild/rebuild!) (core/save!))

;; ---------------------------------------------------------------------------
;; Saved queries

(defn save-query! [domain-id name text]
  (let [name (str name)]
    (swap! app-state update-in [:schema :queries]
           (fn [qs]
             (let [without (vec (remove #(= name (:name %)) (or qs [])))]
               (conj without {:name name :text text :domain domain-id})))))
  (core/save!))

(defn delete-query! [name]
  (swap! app-state update-in [:schema :queries]
         (fn [qs] (vec (remove #(= name (:name %)) (or qs [])))))
  (core/save!))

(defn toggle-pin-query! [name]
  (swap! app-state update-in [:schema :queries]
         (fn [qs]
           (mapv (fn [q] (if (= name (:name q)) (update q :pinned? not) q))
                 (or qs []))))
  (core/save!))

(defn pinned-queries
  "Vec of {:domain-id :name :text} for pinned saved queries."
  []
  (vec (for [q (get-in @app-state [:schema :queries])
             :when (:pinned? q)]
         {:domain-id (:domain q) :name (:name q) :text (:text q)})))

;; ---------------------------------------------------------------------------
;; Rules

(defn set-rules!
  "Replace the rule clauses filed under `domain-id` with `clauses` (a vec of
  bare [(head) body...] vectors). Keeps rules from other domains intact."
  [domain-id clauses]
  (swap! app-state update :rules
         (fn [rs]
           (let [kept (vec (remove #(= domain-id (:domain %)) rs))
                 added (mapv (fn [c] {:id (str (random-uuid))
                                      :domain domain-id
                                      :clause c}) clauses)]
             (into kept added))))
  (rebuild/rebuild!) (core/save!))

;; ---------------------------------------------------------------------------
;; Move between domains (just changes the :domain field on declarations)

(defn move-type! [type-name dst-id]
  (swap! app-state update-in [:schema :types]
         (fn [ts] (mapv (fn [t] (if (= type-name (:name t))
                                  (assoc t :domain dst-id) t)) ts)))
  (rebuild/rebuild!) (core/save!))

(defn move-predicate! [name arity dst-id]
  (swap! app-state update-in [:schema :predicates]
         (fn [ps] (mapv (fn [p] (if (and (= name (:name p))
                                         (= arity (count (:argTypes p))))
                                  (assoc p :domain dst-id) p)) ps)))
  (rebuild/rebuild!) (core/save!))

(defn move-query! [name dst-id]
  (swap! app-state update-in [:schema :queries]
         (fn [qs] (mapv (fn [q] (if (= name (:name q))
                                  (assoc q :domain dst-id) q)) qs)))
  (core/save!))
