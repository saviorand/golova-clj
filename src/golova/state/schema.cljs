(ns golova.state.schema
  "Pure schema helpers: derive the Datahike schema map from declared
  predicates + rule heads, and surface per-attribute info for the UI."
  (:require [golova.state.core :refer [app-state]]))

(def base-schema
  "Built-in attributes always present in the db."
  {:note          {:db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   :label         {:db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   :domain-label  {:db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   :in-domain     {:db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   :domain-parent {:db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}})

(defn- ref-arg-type? [arg-type declared-type-names]
  (or (= "atom" arg-type) (contains? declared-type-names arg-type)))

(defn- arg-type->db-type [arg-type declared-type-names]
  (cond
    (= "int" arg-type)    :db.type/long
    (= "string" arg-type) :db.type/string
    (= "atom" arg-type)   :db.type/ref
    (contains? declared-type-names arg-type) :db.type/ref
    :else :db.type/string))

(defn- predicate-schema-entry [pred-name arg-types declared-type-names]
  (let [t2 (second arg-types)
        ref? (ref-arg-type? t2 declared-type-names)
        cardinality (if ref? :db.cardinality/many :db.cardinality/one)
        vt (arg-type->db-type t2 declared-type-names)]
    {(keyword pred-name)
     {:db/valueType vt :db/cardinality cardinality}}))

(defn- rule-head-attrs [rules]
  (into {}
        (for [r rules
              :when (and (vector? r) (seq r))
              :let [head (first r)]
              :when (and (seq? head) (symbol? (first head)))
              :let [a (keyword (str (first head)))
                    arity (count (rest head))]
              :when (#{1 2} arity)]
          [a arity])))

(defn build-schema
  "Build the global Datahike schema map: base + one entry per declared
  predicate + one entry per rule head."
  [state]
  (let [type-names (set (map :name (get-in state [:schema :types])))
        pred-entries (apply merge
                            (for [p (get-in state [:schema :predicates])]
                              (predicate-schema-entry
                                (:name p) (:argTypes p) type-names)))
        rule-heads (rule-head-attrs (:rules state))
        rule-head-entries (apply merge
                                  (for [[a arity] rule-heads
                                        :when (not (contains? pred-entries a))]
                                    (case arity
                                      1 {a {:db/valueType :db.type/boolean
                                            :db/cardinality :db.cardinality/one}}
                                      2 {a {:db/valueType :db.type/ref
                                            :db/cardinality :db.cardinality/many}})))]
    (merge base-schema pred-entries rule-head-entries)))

(defn attr-schema-info
  "Return the Datahike schema entry for attribute `attr`, or nil."
  [attr]
  (get (:db-schema @app-state) (keyword attr)))

(defn db-type-label [vt]
  (case vt
    :db.type/ref     "ref"
    :db.type/long    "int"
    :db.type/string  "string"
    :db.type/boolean "bool"
    :db.type/float   "float"
    :db.type/double  "double"
    :db.type/keyword "keyword"
    :db.type/uuid    "uuid"
    :db.type/instant "instant"
    (str vt)))
