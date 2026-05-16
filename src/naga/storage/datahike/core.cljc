(ns naga.storage.datahike.core
  "Naga storage adapter for Datahike, cljc port.

  Differs from the JVM-only adapter: this version is *value-based*. It holds
  a Datahike DB value (built from `datahike.core/empty-db`) and transitions
  it with `d/db-with` instead of going through a connection / `d/transact`.
  Datahike-cljs does not support synchronous `transact` on a connection, but
  `db-with` is sync on both JVM and CLJS — which is what Naga's engine needs."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.core :as dcore]
            [naga.store :as store]
            [naga.store-registry :as store-registry]
            [zuko.node :as node :refer [NodeAPI]]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- vartest? [x] (symbol? x))

(defn- pattern->where [t-sym [e a v]]
  [[t-sym :naga/e e]
   [t-sym :naga/a a]
   [t-sym :naga/v v]])

(defn- pattern-find-vars [pattern]
  (->> pattern (filter vartest?) distinct vec))

(defn- triple-tempid-counter []
  (let [a (atom 0)]
    #(swap! a dec)))

(defn- triple->tx [next-id [e a v]]
  {:db/id (next-id) :naga/e e :naga/a a :naga/v v})

(defn- triple-exists? [db [e a v]]
  ;; ":find ?t ." projects to a scalar (entity id or nil) — DO NOT wrap in seq,
  ;; (seq <number>) throws "is not ISeqable" in cljs (and JVM, but only triggers
  ;; when a duplicate inference exists in the DB).
  (boolean
   (d/q '[:find ?t .
          :in $ ?e ?a ?v
          :where [?t :naga/e ?e] [?t :naga/a ?a] [?t :naga/v ?v]]
        db e a v)))

(defn- random-uuid-str []
  #?(:clj  (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))))

;; ---------------------------------------------------------------------------
;; Core operations (pure, db-value -> db-value)

(defn resolve-pattern* [db pattern]
  (let [vars (pattern-find-vars pattern)
        where (pattern->where (gensym "?t") pattern)
        rows (d/q {:find vars :where where} db)
        rebuild (fn [row]
                  (let [bindings (zipmap vars row)]
                    (mapv #(get bindings % %) pattern)))]
    (with-meta (mapv rebuild rows) {:cols pattern})))

(defn count-pattern* [db pattern]
  (let [vars (pattern-find-vars pattern)
        where (pattern->where (gensym "?t") pattern)
        result (if (seq vars)
                 (d/q {:find [(list 'count (first vars)) '.]
                       :with (vec (rest vars))
                       :where where}
                      db)
                 (d/q {:find ['(count ?t) '.]
                       :where (pattern->where '?t pattern)}
                      db))]
    (or result 0)))

(defn query* [db output-pattern patterns]
  (let [out-vars (filter vartest? output-pattern)
        where (vec (mapcat #(pattern->where (gensym "?t") %) patterns))
        rows (if (seq out-vars)
               (d/q {:find (vec out-vars) :where where} db)
               #{[]})
        rebuild (fn [row]
                  (let [bindings (zipmap out-vars row)]
                    (mapv #(get bindings % %) output-pattern)))]
    (with-meta (mapv rebuild rows) {:cols output-pattern})))

(defn assert-data*
  "Insert triples into the db value, deduping against current contents.
  Returns a new db value."
  [db triples]
  (let [next-id (triple-tempid-counter)
        new-triples (remove (partial triple-exists? db) triples)
        tx-data (mapv (partial triple->tx next-id) new-triples)]
    (if (seq tx-data)
      (d/db-with db tx-data)
      db)))

(defn retract-data* [db triples]
  (let [retractions (for [[e a v] triples
                          :let [tid (d/q '[:find ?t .
                                           :in $ ?e ?a ?v
                                           :where [?t :naga/e ?e] [?t :naga/a ?a] [?t :naga/v ?v]]
                                         db e a v)]
                          :when tid]
                      [:db/retractEntity tid])]
    (if (seq retractions)
      (d/db-with db (vec retractions))
      db)))

(defn query-insert* [db assertion-patterns body-patterns]
  (let [body-vars (->> body-patterns
                       (mapcat identity)
                       (filter vartest?)
                       distinct
                       vec)
        where (vec (mapcat #(pattern->where (gensym "?t") %) body-patterns))
        rows (d/q {:find body-vars :where where} db)
        new-triples (for [row rows
                          :let [bindings (zipmap body-vars row)]
                          ap assertion-patterns]
                      (mapv #(get bindings % %) ap))]
    (assert-data* db (vec new-triples))))

;; ---------------------------------------------------------------------------
;; Store record (Naga's storage protocol)

(declare ->DatahikeStore)

(defrecord DatahikeStore [db before-db]
  store/Storage

  (start-tx [_]
    (->DatahikeStore db db))

  (commit-tx [_]
    (->DatahikeStore db nil))

  (deltas [_]
    ;; "subjects of triples added since start-tx"
    ;; In the value-based design we don't have a tx-id, so diff entity sets.
    (when before-db
      (let [old (set (map :e (d/datoms before-db :eavt)))
            new (set (map :e (d/datoms db :eavt)))
            added-eids (set/difference new old)]
        (vec
         (distinct
          (for [eid added-eids
                :let [subj (d/q '[:find ?e .
                                  :in $ ?t
                                  :where [?t :naga/e ?e]]
                                db eid)]
                :when subj]
            subj))))))

  (resolve-pattern [_ pattern]
    (resolve-pattern* db pattern))

  (count-pattern [_ pattern]
    (count-pattern* db pattern))

  (query [_ output-pattern patterns]
    (query* db output-pattern patterns))

  (assert-data [_ data]
    (->DatahikeStore (assert-data* db data) before-db))

  (retract-data [_ data]
    (->DatahikeStore (retract-data* db data) before-db))

  (assert-schema-opts [this _ _] this)

  (query-insert [_ assertion-patterns patterns]
    (->DatahikeStore (query-insert* db assertion-patterns patterns) before-db))

  NodeAPI
  (new-node [_]
    (keyword "naga.node" (random-uuid-str)))

  (node-id [_ n]
    (cond
      (keyword? n) (str (when (namespace n) (str (namespace n) "/")) (name n))
      :else        (str n)))

  (node-type? [_ _ value]
    (keyword? value))

  (data-attribute      [_ _] :naga/first)
  (container-attribute [_ _] :naga/contains)

  (find-triple [_ pattern]
    (resolve-pattern* db pattern)))

;; ---------------------------------------------------------------------------
;; Factories

(def default-db-config
  {:keep-history? true
   :schema-flexibility :read})

(defn empty-store
  "Build a fresh, empty store. Accepts an optional user-config map merged onto
  the defaults (must include :schema-flexibility :read)."
  ([] (empty-store {}))
  ([user-cfg]
   (let [cfg (merge default-db-config user-cfg)
         db  (dcore/empty-db nil cfg)]
     (->DatahikeStore db nil))))

(defn wrap-db
  "Build a store around an existing Datahike DB value."
  [db]
  (->DatahikeStore db nil))

(defn create-store
  "Registry-compatible factory. Ignores any :datahike-cfg :store config
  because this adapter is value-only; only schema flags are honored."
  ([] (create-store {}))
  ([{:keys [datahike-cfg]}]
   (empty-store (select-keys datahike-cfg [:keep-history? :schema-flexibility]))))

(store-registry/register-storage! :datahike create-store)
