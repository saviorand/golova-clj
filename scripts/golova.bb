#!/usr/bin/env bb
;; golova.bb — headless Golova knowledge-base API server
;;
;; Loads a Golova v2 snapshot EDN file, replays events into a Datahike
;; file DB via the datahike babashka pod, materialises rules, then serves
;; a small HTTP API for agent use.
;;
;; Usage:
;;   bb scripts/golova.bb [--snapshot snapshot.edn] [--db /tmp/golova-bb] [--port 7734]
;;
;; Endpoints (all responses are EDN):
;;   GET  /status            → {:entities N :datoms N}
;;   GET  /entities          → {:entities [:alice :bob …]}
;;   GET  /domains           → {:domains [:people :tasks …]}
;;   GET  /pull?e=:alice     → {:db/ident :alice :parent :bob …}
;;   GET  /query?q=<datalog> → {:vars ["e" "n"] :rows [[:alice "Alice"] …]}
;;   POST /transact          → body EDN: {:op :assert/:retract :triple [e a v]}

(require '[babashka.pods :as pods])
(pods/load-pod 'replikativ/datahike "CURRENT")
(require '[datahike.pod :as d])

(require '[babashka.cli   :as cli]
         '[clojure.edn    :as edn]
         '[clojure.java.io :as io]
         '[clojure.string  :as str]
         '[clojure.walk    :as walk]
         '[org.httpkit.server :as http])

;; ============================================================================
;; Schema derivation — port of state/schema.cljs
;; ============================================================================

(def ^:private base-schema-tx
  [{:db/ident :note          :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :label         :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :domain-label  :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :in-domain     :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}
   {:db/ident :domain-parent :db/valueType :db.type/ref    :db/cardinality :db.cardinality/one}])

(defn- arg-type->vtype [t type-names]
  (cond
    (= "int" t)                             :db.type/long
    (= "string" t)                          :db.type/string
    (or (= "atom" t) (contains? type-names t)) :db.type/ref
    :else                                   :db.type/string))

(defn- pred->attr [{:keys [name argTypes]} type-names]
  (let [t2   (second argTypes)
        ref? (or (= "atom" t2) (contains? type-names t2))]
    {:db/ident       (keyword name)
     :db/valueType   (arg-type->vtype t2 type-names)
     :db/cardinality (if ref? :db.cardinality/many :db.cardinality/one)}))

(defn- rule-clause->attr [clause]
  (when (and (vector? clause) (seq clause))
    (let [head (first clause)]
      (when (and (seq? head) (symbol? (first head)))
        (let [a     (keyword (str (first head)))
              arity (count (rest head))]
          (case arity
            1 {:db/ident a :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
            2 {:db/ident a :db/valueType :db.type/ref     :db/cardinality :db.cardinality/many}
            nil))))))

(defn build-schema-tx [snap]
  (let [type-names  (set (map :name (get-in snap [:schema :types])))
        pred-attrs  (mapv #(pred->attr % type-names) (get-in snap [:schema :predicates]))
        pred-idents (set (map :db/ident pred-attrs))
        rule-attrs  (->> (mapv :clause (:rules snap))
                         (keep rule-clause->attr)
                         (remove #(contains? pred-idents (:db/ident %)))
                         distinct)]
    (vec (concat base-schema-tx pred-attrs rule-attrs))))

;; ============================================================================
;; Event normalization — port of state/sync.cljs normalize-event
;; ============================================================================

(defn- normalize-event [e]
  (cond
    (vector? e) {:op :assert :triple e :at 0 :source "hand"}
    (map? e)    (cond-> e
                  (nil? (:op e))     (assoc :op :assert)
                  (nil? (:at e))     (assoc :at 0)
                  (nil? (:source e)) (assoc :source "hand"))
    :else e))

;; ============================================================================
;; Tx helpers — port of state/rebuild.cljs
;; ============================================================================

(defn- triple->tx [[e a v]]
  (let [a (keyword a)]
    (if (keyword? v)
      [{:db/ident e} {:db/ident v} [:db/add [:db/ident e] a [:db/ident v]]]
      [{:db/ident e} [:db/add [:db/ident e] a v]])))

(defn- triple->retract-tx [[e a v]]
  [[:db/retract [:db/ident e] (keyword a) (if (keyword? v) [:db/ident v] v)]])

;; ============================================================================
;; Rule materialisation — port of state/rebuild.cljs
;; ============================================================================

(defn rewrite-rule-call
  "Sugar: (ancestor ?a ?d) → [?a :ancestor ?d], (flag ?a) → [?a :flag true]"
  [c]
  (if (and (seq? c) (not (vector? c)) (symbol? (first c)))
    (let [[h & args] c n (count args)]
      (cond (= n 2) [(first args) (keyword (str h)) (second args)]
            (= n 1) [(first args) (keyword (str h)) true]
            :else   c))
    c))

(defn- expand-rule
  "Run one rule clause against db, return tx datoms for new derived facts."
  [db clause]
  (try
    (let [[head & body] clause
          attr      (keyword (str (first head)))
          head-vars (vec (rest head))
          arity     (count head-vars)]
      (when (#{1 2} arity)
        (let [q    (vec (concat [:find] head-vars [:where] (mapv rewrite-rule-call body)))
              rows (d/q q db)]
          (case arity
            1 (mapv (fn [[a]]   [:db/add a attr true])   rows)
            2 (mapv (fn [[a b]] [:db/add a attr b])       rows)))))
    (catch Exception _ nil)))

(defn- datom-count [conn]
  (count (d/q '[:find ?e ?a ?v :where [?e ?a ?v]] (d/db conn))))

(defn- materialize-rules! [conn rule-clauses]
  (loop [iter 0]
    (when (< iter 25)
      (let [db (d/db conn)
            tx (->> rule-clauses
                    (mapcat #(expand-rule db %))
                    (filter some?)
                    distinct
                    vec)]
        (when (seq tx)
          (let [n0 (datom-count conn)
                _  (try (d/transact conn tx) (catch Exception _))
                n1 (datom-count conn)]
            (when (> n1 n0)
              (recur (inc iter)))))))))

;; ============================================================================
;; Rebuild — replay snapshot into a fresh Datahike file DB
;; ============================================================================

(defn- db-config [db-path]
  {:store              {:backend :file :path db-path}
   :keep-history?      false
   :schema-flexibility :write})

(defn rebuild! [snap db-path]
  (println "Rebuilding DB at" db-path "…")
  (let [cfg (db-config db-path)]
    (when (d/database-exists? cfg) (d/delete-database cfg))
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      ;; 1. schema
      (let [schema-tx (build-schema-tx snap)]
        (d/transact conn schema-tx)
        (println " " (count schema-tx) "schema attrs"))
      ;; 2. type constructors as idents
      (let [ctors (for [t (get-in snap [:schema :types]) c (:constructors t)]
                    {:db/ident (keyword c)})]
        (when (seq ctors) (d/transact conn (vec ctors))))
      ;; 3. replay events
      (let [events (mapv normalize-event (:events snap))
            ok     (atom 0)
            skip   (atom 0)]
        (doseq [{:keys [op triple]} events]
          (try
            (d/transact conn (case op
                               :assert  (triple->tx triple)
                               :retract (triple->retract-tx triple)
                               []))
            (swap! ok inc)
            (catch Exception _ (swap! skip inc))))
        (println " " @ok "events replayed," @skip "skipped"))
      ;; 4. rule materialisation
      (let [rule-clauses (mapv :clause (:rules snap))]
        (when (seq rule-clauses)
          (println "  Materialising" (count rule-clauses) "rules…")
          (materialize-rules! conn rule-clauses)))
      (let [n-ents (count (d/q '[:find ?e :where [?e :db/ident _]] (d/db conn)))]
        (println "Ready." n-ents "named entities.")
        conn))))

;; ============================================================================
;; Query helper — port of state/query.cljs
;; ============================================================================

(defn- free-vars [clauses]
  (let [acc (atom []) seen (atom #{})]
    (walk/postwalk (fn [x]
                     (when (and (symbol? x)
                                (str/starts-with? (name x) "?")
                                (not (@seen x)))
                       (swap! seen conj x)
                       (swap! acc conj x))
                     x)
                   clauses)
    @acc))

(defn- eid->ident-map
  "Build {entity-id → ident-kw} for all named entities in db."
  [db]
  (into {} (d/q '[:find ?e ?i :where [?e :db/ident ?i]] db)))

(defn- resolve-ids
  "Walk a result row replacing numeric entity ids with their idents."
  [id->ident row]
  (mapv #(get id->ident % %) row))

(defn run-query [conn text]
  (try
    (let [db      (d/db conn)
          parsed  (edn/read-string (str "[" (str/trim text) "]"))
          full?   (some #{:find} parsed)
          q       (if full?
                    (vec parsed)
                    (let [cs   (mapv rewrite-rule-call parsed)
                          vars (free-vars cs)]
                      (vec (concat [:find] vars [:where] cs))))
          rows    (vec (d/q q db))
          id->i   (eid->ident-map db)
          rows'   (mapv #(resolve-ids id->i %) rows)
          vars'   (if full?
                    (->> q
                         (drop-while #(not= % :find)) rest
                         (take-while symbol?)
                         (mapv #(subs (name %) 1)))
                    (mapv #(subs (name %) 1)
                          (free-vars (mapv rewrite-rule-call parsed))))]
      {:vars vars' :rows rows'})
    (catch Exception e {:error (.getMessage e)})))

;; ============================================================================
;; Pull helper — resolve ref values back to idents
;; ============================================================================

(defn- resolve-pull-vals
  "Replace {:db/id N} ref maps with the ident keyword, if known."
  [id->ident m]
  (into {} (for [[k v] m
                 :when (not= k :db/id)]
             [k (cond
                  (map? v)    (get id->ident (:db/id v) v)
                  (set? v)    (set (map #(if (map? %) (get id->ident (:db/id %) %) %) v))
                  (vector? v) (mapv #(if (map? %) (get id->ident (:db/id %) %) %) v)
                  :else       v)])))

(defn pull-entity [conn ident-kw]
  (let [db      (d/db conn)
        result  (d/pull db '[*] [:db/ident ident-kw])
        id->i   (eid->ident-map db)]
    (resolve-pull-vals id->i result)))

;; ============================================================================
;; HTTP server
;; ============================================================================

(defn- edn-resp [status body]
  {:status  status
   :headers {"Content-Type" "application/edn"}
   :body    (pr-str body)})

(defn- ok  [body] (edn-resp 200 body))
(defn- bad [msg]  (edn-resp 400 {:error msg}))

(defn- parse-qs [qs]
  (when qs
    (into {} (for [part (str/split qs #"&")
                   :let [[k v] (str/split part #"=" 2)]
                   :when (and k v)]
               [k (java.net.URLDecoder/decode v "UTF-8")]))))

(defn make-handler [conn-atom]
  (fn [{:keys [uri request-method query-string body]}]
    (let [conn @conn-atom
          qs   (parse-qs query-string)]
      (try
        (cond
          ;; GET /status
          (and (= :get request-method) (= "/status" uri))
          (let [db (d/db conn)]
            (ok {:entities (count (d/q '[:find ?e :where [?e :db/ident _]] db))
                 :datoms   (count (d/q '[:find ?e ?a ?v :where [?e ?a ?v]] db))}))

          ;; GET /entities
          (and (= :get request-method) (= "/entities" uri))
          (let [rows (d/q '[:find ?i :where [_ :db/ident ?i]] (d/db conn))]
            (ok {:entities (sort (map first rows))}))

          ;; GET /domains
          (and (= :get request-method) (= "/domains" uri))
          (let [rows (d/q '[:find ?i :where [?e :db/ident ?i] [?e :domain-label _]] (d/db conn))]
            (ok {:domains (sort (map first rows))}))

          ;; GET /pull?e=:alice
          (and (= :get request-method) (= "/pull" uri))
          (if-let [e-str (get qs "e")]
            (let [kw (keyword (str/replace e-str #"^:" ""))]
              (ok (pull-entity conn kw)))
            (bad "missing ?e= parameter"))

          ;; GET /query?q=<datalog>
          (and (= :get request-method) (= "/query" uri))
          (if-let [q (get qs "q")]
            (ok (run-query conn q))
            (bad "missing ?q= parameter"))

          ;; POST /transact  body: {:op :assert :triple [:alice :age 30]}
          (and (= :post request-method) (= "/transact" uri))
          (let [{:keys [op triple]} (edn/read-string (slurp body))
                tx (case (keyword op)
                     :assert  (triple->tx triple)
                     :retract (triple->retract-tx triple)
                     (throw (ex-info "op must be :assert or :retract" {})))]
            (d/transact conn tx)
            (ok {:ok true}))

          :else {:status 404 :body "Not found"})
        (catch Exception e
          (edn-resp 500 {:error (.getMessage e)}))))))

;; ============================================================================
;; Main
;; ============================================================================

(def ^:private cli-spec
  {:snapshot {:default "snapshot.edn" :desc "Path to Golova v2 snapshot EDN"}
   :db       {:default "/tmp/golova-bb"  :desc "Directory for Datahike file store"}
   :port     {:default 7734 :coerce :int :desc "HTTP port"}})

(let [opts      (cli/parse-opts *command-line-args* {:spec cli-spec})
      snap-path (:snapshot opts)
      db-path   (:db opts)
      port      (:port opts)]
  (when-not (.exists (io/file snap-path))
    (println "Error: snapshot file not found:" snap-path)
    (println "Export from Golova via Settings → Export, then pass --snapshot <path>")
    (System/exit 1))
  (let [snap (edn/read-string (slurp snap-path))]
    (when-not (= 2 (:version snap))
      (println "Error: only v2 snapshots are supported.")
      (println "If you have a v1 snapshot, import it into Golova and re-export.")
      (System/exit 1))
    (let [conn-atom (atom (rebuild! snap db-path))
          handler   (make-handler conn-atom)
          _         (http/run-server handler {:port port})]
      (println (str "Listening on http://localhost:" port))
      (println "  GET  /status")
      (println "  GET  /entities")
      (println "  GET  /domains")
      (println "  GET  /pull?e=:alice")
      (println "  GET  /query?q=[?e+:parent+?p]")
      (println "  POST /transact  (EDN body: {:op :assert :triple [e a v]})")
      @(promise))))
