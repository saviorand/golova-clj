(ns golova.state.rebuild
  "The data-flow engine: tx helpers, rule materialisation, and the global
  rebuild! that derives :db and :db-schema from the event log + program.
  append-events!/append-event! live here too, since they finish by
  triggering a rebuild."
  (:require [datahike.core :as d]
            [golova.state.core :refer [app-state]]
            [golova.state.schema :as schema]))

;; --- Tx helpers ----------------------------------------------------------

(defn- triple->tx [[e a v]]
  (let [a (keyword a)]
    (cond
      (keyword? v) [{:db/ident e} {:db/ident v} [:db/add e a v]]
      :else        [{:db/ident e} [:db/add e a v]])))

(defn- triple->retract-tx [[e a v]] [[:db/retract e (keyword a) v]])

(defn- apply-tx [db tx rejections]
  (try [(d/db-with db tx) rejections]
       (catch :default e
         (let [msg (or (.-message e) (str e))
               data (ex-data e)]
           (js/console.warn "tx failed:" tx msg)
           [db (conj (or rejections [])
                     {:message msg
                      :error-type (:error data)
                      :attribute (:attribute data)
                      :value (:value data)
                      :context (:context data)})]))))

(defn- apply-tx-discard [db tx]
  (first (apply-tx db tx nil)))

;; --- Rule materialisation ------------------------------------------------

(defn rewrite-rule-call
  "Sugar: rewrite `(head ?a ?b)` → `[?a :head ?b]` for arity-2 rule calls,
  and `(head ?a)` → `[?a :head true]` for arity-1 (matches the boolean flag
  the materializer emits for arity-1 rules). Plain patterns pass through.

  Public so the query layer can reuse the same rewrite at query time."
  [c]
  (if (and (seq? c) (symbol? (first c)))
    (let [[hname & args] c
          n (count args)]
      (cond
        (= n 2) [(first args) (keyword (str hname)) (second args)]
        (= n 1) [(first args) (keyword (str hname)) true]
        :else c))
    c))

(defn- expand-rule [db rule]
  (try
    (let [[head & body] rule
          head-name (str (first head))
          attr (keyword head-name)
          head-vars (vec (rest head))
          arity (count head-vars)]
      (if-not (#{1 2} arity)
        []
        (let [body' (map rewrite-rule-call body)
              q (vec (concat [:find] head-vars [:where] body'))
              rows (d/q q db)]
          (case arity
            1 (mapv (fn [[a]]   [:db/add a attr true]) rows)
            2 (mapv (fn [[a b]] [:db/add a attr b])    rows)))))
    (catch :default _ [])))

(defn- materialize-rules [db rules]
  (loop [db db iter 0]
    (if (> iter 25)
      db
      (let [tx (->> rules (mapcat #(expand-rule db %)) distinct vec)]
        (if (empty? tx)
          db
          (let [n-before (count (d/datoms db :eavt))
                db' (try (d/db-with db tx) (catch :default _ db))
                n-after (count (d/datoms db' :eavt))]
            (if (= n-before n-after) db' (recur db' (inc iter)))))))))

;; --- Rebuild -------------------------------------------------------------

(defn- rebuild-state
  "Pure: given a snapshot of the data + program, return [db schema rejections error]."
  [state]
  (try
    (let [sch (schema/build-schema state)
          db0 (d/empty-db sch {:schema-flexibility :write})
          ctor-tx (for [t (get-in state [:schema :types])
                        c (:constructors t)]
                    {:db/ident (keyword c)})
          db1 (if (seq ctor-tx) (apply-tx-discard db0 (vec ctor-tx)) db0)
          rejections (atom [])
          db2 (reduce
                (fn [db {:keys [op triple]}]
                  (case op
                    :assert
                    (let [[db' rej] (apply-tx db (triple->tx triple) @rejections)]
                      (reset! rejections rej)
                      db')
                    :retract
                    (apply-tx-discard db (triple->retract-tx triple))
                    db))
                db1
                (:events state))
          db3 (materialize-rules db2 (:rules state))]
      [db3 sch (vec @rejections) nil])
    (catch :default e
      (js/console.error "rebuild failed:" (or (.-message e) (str e)))
      [nil nil [] (or (.-message e) (str e))])))

;; --- Rules: representation -----------------------------------------------
;;
;; Internally rules are stored in :rules as a vector of maps
;; {:id <uuid> :domain <kw-or-nil> :clause [(head ?a) [?a ...] ...]}.
;; The materializer wants bare clauses, so we flatten here before passing
;; in.

(defn- rule-clauses [state]
  (mapv :clause (:rules state)))

(defn- with-bare-rules [state]
  (assoc state :rules (rule-clauses state)))

(defn rebuild!
  "Flatten the wrapped rules and recompute :db, :db-schema, :rejections,
  :build-error / :error from the current event log + program."
  []
  (let [src @app-state
        flat (with-bare-rules src)
        [db sch rejections err] (rebuild-state flat)]
    (swap! app-state assoc
           :db db :db-schema sch
           :rejections rejections
           :build-error err
           :error err)))

(defn append-events!
  "Append `evts` to the event log and trigger a rebuild. Events whose `:id`
  is already in the log are silently dropped — this makes deterministic-id
  producers (e.g. source refresh) idempotent on re-run."
  [evts]
  (let [existing (set (map :id (:events @app-state)))
        new-evts (vec (remove #(contains? existing (:id %)) evts))]
    (when (seq new-evts)
      (swap! app-state update :events (fnil into []) new-evts)
      (rebuild!))))

(defn append-event! [evt] (append-events! [evt]))
