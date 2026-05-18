(ns golova.state
  "Application state for Golova — built directly on Datahike.

  Each domain owns a Datahike immutable db value plus UI-side metadata:
  - :db        — the Datahike db (entities + datoms)
  - :db-schema — the Datahike schema map currently installed on :db
  - :rules     — vector of Datalog rule clauses, edited as EDN
  - :events    — assert/retract event log (for persistence + replay)
  - :imports   — cross-domain imports (vec of {:from <id> :predicates <set|:all>})
  - :schema    — UI metadata: types (constructors), predicates (declared
                 arg types), saved queries

  Named entities have a :atom/name keyword that is unique:identity.
  All other attributes are user-declared; ref attrs hold lookup refs to
  named entities, scalar attrs hold ints / strings / keywords.

  Persistence: serializable per-domain shape is written to localStorage via
  `storage/-save`. On load we replay the event log to rebuild :db; this is
  effectively idempotent and keeps the persisted blob small."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [datahike.core :as d]
            [golova.storage :as storage]))

;; ---------------------------------------------------------------------------
;; State atom

(defonce app-state
  (r/atom
   {:device-id nil
    :backend nil
    :domains {}
    :current-domain nil
    :selection {:kind :home}
    :theme :light
    :expanded #{}
    :expanded-subs #{}
    :modal nil
    :top-query {:text "" :result nil}
    :last-saved nil
    :error nil
    :first-run? false
    :home {:onboarding-collapsed? true}}))

(defn current []
  (let [s @app-state]
    (get-in s [:domains (:current-domain s)])))

(defn current-id [] (:current-domain @app-state))
(defn device-id  [] (:device-id @app-state))

;; ---------------------------------------------------------------------------
;; Schema bootstrap

(def base-schema
  "Schema entries that are always present. Under :write mode every entry
  must have :db/valueType + :db/cardinality."
  {:note {:db/valueType :db.type/string :db/cardinality :db.cardinality/one}})

(defn- ref-arg-type?
  "True if the named arg type stores references to named entities (atoms)
  rather than scalar values."
  [arg-type declared-type-names]
  (or (= "atom" arg-type)
      (contains? declared-type-names arg-type)))

(defn- arg-type->db-type
  "Map a UI arg-type string to a Datahike :db/valueType keyword.
  Under :write mode every schema entry needs :db/valueType."
  [arg-type declared-type-names]
  (cond
    (= "int" arg-type)    :db.type/long
    (= "string" arg-type) :db.type/string
    (= "atom" arg-type)   :db.type/ref
    (contains? declared-type-names arg-type) :db.type/ref
    :else :db.type/string))

(defn- predicate-schema
  "Datahike schema entry for one declared user predicate.

  Under :write mode every entry needs :db/valueType + :db/cardinality.
  Ref-typed predicates get cardinality :many; scalar predicates get :one."
  [pred-name arg-types declared-type-names]
  (let [t2 (second arg-types)
        ref? (ref-arg-type? t2 declared-type-names)
        cardinality (if ref? :db.cardinality/many :db.cardinality/one)
        vt (arg-type->db-type t2 declared-type-names)]
    {(keyword pred-name)
     {:db/valueType vt
      :db/cardinality cardinality}}))

(defn- rule-head-attrs
  "Map of {attr-kw arity} for each rule head, so we can auto-install
  schema entries. Supports arity-1 (boolean flag) and arity-2 (ref many)."
  [rules]
  (into {}
        (for [r rules
              :when (and (vector? r) (seq r))
              :let [head (first r)]
              :when (and (seq? head) (symbol? (first head)))
              :let [a (keyword (str (first head)))
                    arity (count (rest head))]
              :when (#{1 2} arity)]
          [a arity])))

(defn- build-schema
  "Build the full Datahike schema map for a domain: base + one entry per
  declared predicate + one entry per rule head (so materialised derivations
  can be transacted)."
  [domain]
  (let [declared-type-names (set (map :name (get-in domain [:schema :types])))
        pred-entries (apply merge
                            (for [p (get-in domain [:schema :predicates])]
                              (predicate-schema (:name p) (:argTypes p)
                                                declared-type-names)))
        rule-heads (rule-head-attrs (:rules domain))
        rule-head-entries (apply merge
                                  (for [[a arity] rule-heads
                                        :when (not (contains? pred-entries a))]
                                    (case arity
                                      ;; arity-1: boolean flag
                                      1 {a {:db/valueType :db.type/boolean
                                            :db/cardinality :db.cardinality/one}}
                                      ;; arity-2: ref relation
                                      2 {a {:db/valueType :db.type/ref
                                            :db/cardinality :db.cardinality/many}})))]
    (merge base-schema pred-entries rule-head-entries)))

;; ---------------------------------------------------------------------------
;; Tx helpers

(defn- triple->tx
  "Convert an [E A V] triple into Datahike tx-data. Each atom (subject and,
  if a keyword, value) is pre-registered with `:db/ident` so that
  Datahike can resolve plain keywords in queries and ref positions."
  [[e a v]]
  (let [a (keyword a)]
    (cond
      (keyword? v)
      [{:db/ident e} {:db/ident v} [:db/add e a v]]
      :else
      [{:db/ident e} [:db/add e a v]])))

(defn- triple->retract-tx
  [[e a v]]
  [[:db/retract e (keyword a) v]])

;; ---------------------------------------------------------------------------
;; Build / rebuild a domain's db from its event log

(defn- apply-tx
  "Apply tx to db. On success returns [db' rejections]. On schema
  validation failure, returns [db rejections] — the db is unchanged
  and the rejection is recorded for UI display."
  [db tx rejections]
  (try
    (let [db' (d/db-with db tx)]
      [db' rejections])
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

(defn- apply-tx-discard
  "Legacy: apply tx, discard rejections. Used for ctor bootstrap and imports
  where we don't need to report errors."
  [db tx]
  (first (apply-tx db tx nil)))

(defn- import-tx
  "Tx-data to copy facts from a source domain into the importing one,
  rewriting attribute names with the source's namespace prefix.
  Currently a no-op stub — we copy nothing; the user can re-add support
  later if they need it."
  [_all-domains _imports]
  [])

;; --- Rule materialisation (fixed-point evaluation) ------------------------

(defn- rewrite-rule-call
  "If `c` is a rule-call like `(ancestor ?a ?b)`, rewrite it to the
  equivalent attr-pattern `[?a :ancestor ?b]`. Datalog-rules-via-magic
  are buggy in Datahike CLJS (`demand_set.size`), so we materialise rule
  outputs and let bodies refer to them as plain attrs. Plain patterns
  pass through unchanged."
  [c]
  (if (and (seq? c) (symbol? (first c)) (= 2 (count (rest c))))
    (let [[hname & args] c]
      [(first args) (keyword (str hname)) (second args)])
    c))

(defn- expand-rule
  "Run a rule's body against db. Returns tx-data asserting head facts.
  Arity-1 rules produce `[?x attr true]`; arity-2 produce `[?x attr ?y]`."
  [db rule]
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

(defn- materialize-rules
  "Run rules to fixed point. Each iteration runs every rule, collects
  asserted-but-not-yet-present tx-data, applies it. Stops when nothing
  new is derived or after a safety cap of 25 iterations."
  [db rules]
  (loop [db db iter 0]
    (if (> iter 25)
      db
      (let [tx (->> rules
                    (mapcat #(expand-rule db %))
                    distinct
                    vec)]
        (if (empty? tx)
          db
          (let [n-before (count (d/datoms db :eavt))
                db' (try (d/db-with db tx) (catch :default _ db))
                n-after (count (d/datoms db' :eavt))]
            (if (= n-before n-after)
              db'
              (recur db' (inc iter)))))))))

(defn- rebuild-domain
  "Rebuild :db from scratch: empty db with schema, then replay the event
  log (asserts + retracts), then any cross-domain imports. Collects
  schema validation rejections for UI display."
  [domain all-domains]
  (try
    (let [schema (build-schema domain)
          db0 (d/empty-db schema {:schema-flexibility :write})
          ;; ensure declared type constructors exist as atoms
          ctor-tx (for [t (get-in domain [:schema :types])
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
                (:events domain))
          db3 (apply-tx-discard db2 (import-tx all-domains (:imports domain)))
          db4 (materialize-rules db3 (:rules domain))]
      (assoc domain :db db4 :db-schema schema
             :build-error nil
             :rejections (vec @rejections)))
    (catch :default e
      (js/console.error "rebuild failed:" (or (.-message e) (str e)))
      (assoc domain :build-error (or (.-message e) (str e)) :rejections []))))

(defn rebuild! []
  (let [doms (:domains @app-state)
        doms' (reduce-kv
                (fn [acc id d] (assoc acc id (rebuild-domain d doms)))
                doms doms)]
    (swap! app-state assoc :domains doms')
    (let [errors (->> (vals doms') (keep :build-error))]
      (swap! app-state assoc :error (when (seq errors) (first errors))))))

(defn attr-schema-info
  "Return the Datahike schema entry for attribute `attr` in the given domain,
  or nil if the attribute has no schema entry. Useful for UI to display
  valueType / cardinality badges."
  [domain-id attr]
  (let [schema (get-in @app-state [:domains domain-id :db-schema])]
    (get schema (keyword attr))))

(defn db-type-label
  "Human-readable label for a Datahike :db/valueType keyword."
  [vt]
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

;; ---------------------------------------------------------------------------
;; Events

(defn- mk-event
  ([op data] (mk-event op data nil))
  ([op data source]
   (merge {:id (str (random-uuid))
           :device (device-id)
           :at (.now js/Date)
           :op op
           :source source}
          data)))

(defn- safe-name [s]
  (-> (or s "") str/lower-case
      (str/replace #"[^a-z0-9_-]+" "_")
      (str/replace #"^_+|_+$" "")))

(defn- fresh-domain-id [label]
  (let [base (let [b (safe-name label)] (if (str/blank? b) "domain" b))
        taken (set (keys (:domains @app-state)))]
    (loop [id (keyword base) i 2]
      (if (taken id)
        (recur (keyword (str base "-" i)) (inc i))
        id))))

;; ---------------------------------------------------------------------------
;; Persistence

(defn serializable [state]
  {:device-id (:device-id state)
   :current-domain (:current-domain state)
   :selection (:selection state)
   :theme (:theme state)
   :expanded (vec (:expanded state))
   :expanded-subs (vec (:expanded-subs state))
   :home (:home state)
   :domains (into {}
                  (for [[id d] (:domains state)]
                    [id (select-keys d [:id :label :rules :events
                                        :imports :schema])]))})

(defn save! []
  (when-let [b (:backend @app-state)]
    (storage/-save b (serializable @app-state))
    (swap! app-state assoc :last-saved (.now js/Date))))

;; ---------------------------------------------------------------------------
;; Starter domain

(def ^:private starter-rules
  '[[(ancestor ?x ?y) [?x :parent ?y]]
    [(ancestor ?x ?z) [?x :parent ?y] (ancestor ?y ?z)]])

(def ^:private starter-events
  (let [mk (fn [e a v]
             {:id (str (random-uuid))
              :at 0
              :op :assert
              :source "starter"
              :triple [e a v]})]
    [(mk :alice :parent :bob)
     (mk :alice :parent :carol)
     (mk :bob :parent :dave)]))

(defn- starter-domain [id label]
  {:id id
   :label label
   :rules starter-rules
   :events starter-events
   :imports []
   :schema {:types [{:name "person" :constructors ["alice" "bob" "carol" "dave"]}]
            :predicates [{:name "parent" :argTypes ["person" "person"]}]
            :queries [{:name "alice's descendants"
                       :text "[[:atom/name :alice] :ancestor ?d]"}
                      {:name "dave's ancestors"
                       :text "[?a :ancestor [:atom/name :dave]]"}
                      {:name "alice's parents"
                       :text "[[:atom/name :alice] :parent ?p]"}]}})

(defn- empty-domain [id label]
  {:id id :label label
   :rules [] :events [] :imports []
   :schema {:types [] :predicates [] :queries []}})

;; ---------------------------------------------------------------------------
;; Bootstrap

(defn load-or-seed! [backend]
  (let [snap (storage/-load backend)]
    (swap! app-state assoc :backend backend)
    (if snap
      ;; Returning user: load their state as-is. An empty :domains map is a
      ;; valid choice (post-reset) — don't re-seed the starter.
      (swap! app-state assoc
             :current-domain (:current-domain snap)
             :selection (or (:selection snap) {:kind :home})
             :theme (or (:theme snap) :light)
             :expanded (set (:expanded snap))
             :expanded-subs (set (map vec (:expanded-subs snap)))
             :home (merge {:onboarding-collapsed? true} (:home snap))
             :domains (or (:domains snap) {}))
      ;; First run: seed the starter so there's something to look at.
      (let [id :starter]
        (swap! app-state assoc
               :domains {id (starter-domain id "Starter")}
               :current-domain id
               :expanded #{}
               :selection {:kind :home}
               :first-run? true)))
    (when (and (:current-domain @app-state)
               (not (contains? (:domains @app-state) (:current-domain @app-state))))
      (swap! app-state assoc :current-domain (first (keys (:domains @app-state)))))))

;; ---------------------------------------------------------------------------
;; UI selection / theme / expansion

(defn select! [sel]
  (swap! app-state assoc :selection sel)
  (save!))

(defn toggle-domain! [id]
  (swap! app-state update :expanded
         (fn [s] (let [s (or s #{})]
                   (if (contains? s id) (disj s id) (conj s id)))))
  (save!))

(defn switch-domain! [id]
  (swap! app-state assoc :current-domain id :selection {:kind :rules} :error nil)
  (save!))

(defn expand-domain! [id]
  (swap! app-state update :expanded (fnil conj #{}) id)
  (save!))

(defn toggle-subsection!
  "Toggle expanded state for one [domain-id sub-key] subsection in the sidebar."
  [domain-id sub-key]
  (let [k [domain-id sub-key]]
    (swap! app-state update :expanded-subs
           (fn [s] (let [s (or s #{})]
                     (if (contains? s k) (disj s k) (conj s k))))))
  (save!))

(defn expand-subsection! [domain-id sub-key]
  (swap! app-state update :expanded-subs (fnil conj #{}) [domain-id sub-key])
  (save!))

(defn set-theme! [t]
  (swap! app-state assoc :theme t)
  (save!))

(defn open-modal! [m] (swap! app-state assoc :modal m))
(defn close-modal! [] (swap! app-state assoc :modal nil))

(defn go-home! []
  (swap! app-state assoc :selection {:kind :home})
  (save!))

(defn toggle-onboarding! []
  (swap! app-state update-in [:home :onboarding-collapsed?] not)
  (save!))


(defn open-palette! [] (swap! app-state assoc :palette {:open? true :query "" :index 0}))
(defn close-palette! [] (swap! app-state assoc :palette nil))
(defn toggle-palette! []
  (if (get-in @app-state [:palette :open?]) (close-palette!) (open-palette!)))
(defn set-palette-query! [q] (swap! app-state update :palette assoc :query q :index 0))
(defn palette-move! [delta] (swap! app-state update-in [:palette :index] (fnil + 0) delta))
(defn palette-set-index! [i] (swap! app-state assoc-in [:palette :index] i))

;; ---------------------------------------------------------------------------
;; Domain CRUD

(defn create-domain! [label]
  (let [id (fresh-domain-id label)]
    (swap! app-state assoc-in [:domains id] (empty-domain id label))
    (swap! app-state assoc :current-domain id)
    (rebuild!)
    (save!)
    id))

(defn delete-domain! [id]
  (let [doms (dissoc (:domains @app-state) id)
        doms (reduce-kv
               (fn [acc dom-id d]
                 (assoc acc dom-id
                        (update d :imports
                                (fn [imps] (vec (remove #(= id (:from %)) imps))))))
               doms doms)]
    (swap! app-state assoc :domains doms)
    (when (= id (:current-domain @app-state))
      (swap! app-state assoc :current-domain (first (keys doms))))
    (rebuild!)
    (save!)))

(defn rename-domain! [id new-label]
  (swap! app-state assoc-in [:domains id :label] new-label)
  (save!))

;; ---------------------------------------------------------------------------
;; Rules

(defn set-rules!
  "Replace the rule vector for a domain."
  [domain-id rules]
  (swap! app-state assoc-in [:domains domain-id :rules] rules)
  (rebuild!)
  (save!))

;; ---------------------------------------------------------------------------
;; Facts (assert / retract / replace via the event log)

(defn- append-event! [domain-id evt]
  (swap! app-state update-in [:domains domain-id :events] (fnil conj []) evt)
  (rebuild!)
  (save!))

(defn assert-triple! [domain-id triple source]
  (append-event! domain-id (mk-event :assert {:triple (vec triple)} source)))

(defn retract-triple! [domain-id triple]
  (append-event! domain-id (mk-event :retract {:triple (vec triple)})))

(defn replace-triple!
  "Retract `old` and assert `new` in a single rebuild."
  [domain-id old new]
  (swap! app-state update-in [:domains domain-id :events] (fnil into [])
         [(mk-event :retract {:triple (vec old)})
          (mk-event :assert {:triple (vec new)} "edit")])
  (rebuild!)
  (save!))

(defn set-note!
  "Set or clear the markdown note attached to an entity. Empty string
  retracts the note. Stored under attr `:note` (string, cardinality :one
  — one note per entity for now)."
  [domain-id entity markdown]
  (let [trimmed (str/trim (or markdown ""))
        db (get-in @app-state [:domains domain-id :db])
        existing (some-> db (d/datoms :eavt entity :note) first :v)]
    (cond
      (and (str/blank? trimmed) existing)
      (retract-triple! domain-id [entity :note existing])
      (str/blank? trimmed) nil
      existing
      (replace-triple! domain-id [entity :note existing] [entity :note trimmed])
      :else
      (assert-triple! domain-id [entity :note trimmed] "note"))))

;; ---------------------------------------------------------------------------
;; Form helpers

(defn- type-decl [domain-id tname]
  (when tname
    (first (filter #(= tname (:name %))
                   (get-in @app-state [:domains domain-id :schema :types])))))

(defn declared-type? [domain-id tname]
  (boolean (type-decl domain-id tname)))

(defn coerce-value
  "Parse a raw form-input string into the stored value, per arg type."
  [domain-id arg-type raw]
  (let [raw (str/trim (or raw ""))]
    (cond
      (str/blank? raw)
      (throw (ex-info "empty value" {}))

      (= "int" arg-type)
      (let [n (js/parseFloat raw)]
        (when (or (js/isNaN n) (not (re-matches #"-?\d+(\.\d+)?" raw)))
          (throw (ex-info (str "not a number: " raw) {})))
        (if (re-matches #"-?\d+" raw) (js/parseInt raw 10) n))

      (= "string" arg-type) raw

      (or (= "atom" arg-type) (declared-type? domain-id arg-type))
      (let [s (str/replace raw #"^:" "")]
        (if-let [slash (str/index-of s "/")]
          (keyword (subs s 0 slash) (subs s (inc slash)))
          (keyword s)))

      :else raw)))

(defn extend-type! [domain-id type-name ctor]
  (let [ctor (str/trim (str ctor))]
    (when (seq ctor)
      (swap! app-state update-in [:domains domain-id :schema :types]
             (fn [ts]
               (mapv (fn [t]
                       (if (= type-name (:name t))
                         (update t :constructors
                                 (fn [cs] (vec (distinct (conj (or cs []) ctor)))))
                         t))
                     (or ts []))))
      (rebuild!)
      (save!))))

(defn assert-from-form!
  "Assert via the predicate add-row form: coerce form values per declared
  arg types, then assert. Arity-2 only."
  [domain-id pred-name arg-types raw-args]
  (when (not= 2 (count arg-types))
    (throw (ex-info "only arity-2 is supported today" {:arity (count arg-types)})))
  (let [[t1 t2] arg-types
        [r1 r2] raw-args
        v1 (coerce-value domain-id t1 r1)
        v2 (coerce-value domain-id t2 r2)
        attr (keyword pred-name)]
    (assert-triple! domain-id [v1 attr v2] "form")))

;; ---------------------------------------------------------------------------
;; Move declared item between domains

(defn move-type! [src-id dst-id name]
  (when-let [t (first (filter #(= name (:name %))
                              (get-in @app-state [:domains src-id :schema :types])))]
    (swap! app-state update-in [:domains src-id :schema :types]
           (fn [ts] (vec (remove #(= name (:name %)) ts))))
    (swap! app-state update-in [:domains dst-id :schema :types]
           (fn [ts] (let [without (vec (remove #(= name (:name %)) (or ts [])))]
                      (conj without t))))
    (rebuild!)
    (save!)))

(defn move-predicate! [src-id dst-id name arity]
  (when-let [p (first (filter #(and (= name (:name %))
                                    (= arity (count (:argTypes %))))
                              (get-in @app-state [:domains src-id :schema :predicates])))]
    (swap! app-state update-in [:domains src-id :schema :predicates]
           (fn [ps] (vec (remove #(and (= name (:name %))
                                       (= arity (count (:argTypes %)))) ps))))
    (swap! app-state update-in [:domains dst-id :schema :predicates]
           (fn [ps] (let [without (vec (remove #(and (= name (:name %))
                                                     (= arity (count (:argTypes %))))
                                                (or ps [])))]
                      (conj without p))))
    (rebuild!)
    (save!)))

(defn move-query! [src-id dst-id name]
  (when-let [q (first (filter #(= name (:name %))
                              (get-in @app-state [:domains src-id :schema :queries])))]
    (swap! app-state update-in [:domains src-id :schema :queries]
           (fn [qs] (vec (remove #(= name (:name %)) qs))))
    (swap! app-state update-in [:domains dst-id :schema :queries]
           (fn [qs] (let [without (vec (remove #(= name (:name %)) (or qs [])))]
                      (conj without q))))
    (save!)))

;; ---------------------------------------------------------------------------
;; Reset / import

(defn reset-all!
  "Wipe everything: every domain, the event log, all UI state. Lands on
  Home with no current domain so the user can create one from scratch."
  []
  (when-let [b (:backend @app-state)] (storage/-clear b))
  (swap! app-state assoc
         :domains {}
         :current-domain nil
         :selection {:kind :home}
         :expanded #{}
         :expanded-subs #{}
         :top-query {:text "" :result nil}
         :modal nil
         :popover nil
         :palette nil
         :home {:onboarding-collapsed? true}
         :error nil)
  (save!))

(defn import-snapshot! [snap]
  (swap! app-state assoc
         :domains (:domains snap)
         :current-domain (:current-domain snap)
         :selection (or (:selection snap) {:kind :rules})
         :theme (or (:theme snap) (:theme @app-state))
         :expanded (set (:expanded snap))
         :expanded-subs (set (map vec (:expanded-subs snap)))
         :error nil)
  (when (and (:current-domain @app-state)
             (not (contains? (:domains @app-state) (:current-domain @app-state))))
    (swap! app-state assoc :current-domain (first (keys (:domains @app-state)))))
  (rebuild!)
  (save!))

;; ---------------------------------------------------------------------------
;; Schema CRUD (UI metadata)

(defn declare-type! [domain-id name constructors]
  (let [name (str name)
        ctors (vec (remove str/blank? constructors))]
    (swap! app-state update-in [:domains domain-id :schema :types]
           (fn [ts]
             (let [without (vec (remove #(= name (:name %)) (or ts [])))]
               (conj without {:name name :constructors ctors})))))
  (rebuild!)
  (save!))

(defn delete-type! [domain-id name]
  (swap! app-state update-in [:domains domain-id :schema :types]
         (fn [ts] (vec (remove #(= name (:name %)) (or ts [])))))
  (rebuild!)
  (save!))

(defn declare-predicate! [domain-id name arg-types]
  (let [name (str name)
        arg-types (vec (remove str/blank? arg-types))
        arity (count arg-types)]
    (swap! app-state update-in [:domains domain-id :schema :predicates]
           (fn [ps]
             (let [without (vec (remove #(and (= name (:name %))
                                              (= arity (count (:argTypes %))))
                                        (or ps [])))]
               (conj without {:name name :argTypes arg-types})))))
  (rebuild!)
  (save!))

(defn delete-predicate! [domain-id name arity]
  (swap! app-state update-in [:domains domain-id :schema :predicates]
         (fn [ps] (vec (remove #(and (= name (:name %))
                                     (= arity (count (:argTypes %))))
                               (or ps [])))))
  (rebuild!)
  (save!))

(defn save-query! [domain-id name text]
  (let [name (str name)]
    (swap! app-state update-in [:domains domain-id :schema :queries]
           (fn [qs]
             (let [without (vec (remove #(= name (:name %)) (or qs [])))]
               (conj without {:name name :text text})))))
  (save!))

(defn delete-query! [domain-id name]
  (swap! app-state update-in [:domains domain-id :schema :queries]
         (fn [qs] (vec (remove #(= name (:name %)) (or qs [])))))
  (save!))

(defn toggle-pin-query!
  "Flip the :pinned? flag on a saved query. Pinned queries surface on the
  Home view with inline results."
  [domain-id name]
  (swap! app-state update-in [:domains domain-id :schema :queries]
         (fn [qs]
           (mapv (fn [q] (if (= name (:name q))
                           (update q :pinned? not)
                           q))
                 (or qs []))))
  (save!))

(defn pinned-queries
  "Seq of {:domain-id :name :text} for every saved query with :pinned?
  true, across all domains."
  []
  (vec (for [[id d] (:domains @app-state)
             q (get-in d [:schema :queries])
             :when (:pinned? q)]
         {:domain-id id :name (:name q) :text (:text q)})))

;; ---------------------------------------------------------------------------
;; Triple inspection (callers in ui.cljs)

(defn all-triples
  "Return all datoms in the domain's db as [e a v] vectors. The entity
  side is rendered as its `:db/ident` keyword when present, otherwise the
  numeric eid. Ref values are likewise resolved to idents."
  [domain-id]
  (let [db (get-in @app-state [:domains domain-id :db])]
    (if-not db
      []
      (let [ident-of (fn [eid]
                       (or (:v (first (d/datoms db :eavt eid :db/ident)))
                           eid))]
        (->> (d/datoms db :eavt)
             ;; hide :db/ident self-naming datoms; they render the entity,
             ;; they're not user-visible facts.
             (remove #(= :db/ident (:a %)))
             (mapv (fn [d]
                     (let [v (:v d)
                           a (:a d)
                           schema (get-in @app-state [:domains domain-id :db-schema])
                           ref? (= :db.type/ref (get-in schema [a :db/valueType]))]
                       [(ident-of (:e d))
                        a
                        (if ref? (ident-of v) v)]))))))))

(defn entity-mentions
  "Return all triples in which `entity` (a keyword) appears as subject or
  object — used by the entity view."
  [domain-id entity]
  (let [trs (all-triples domain-id)]
    (filterv (fn [[e _ v]] (or (= e entity) (= v entity))) trs)))

(defn entity-note
  "Return the markdown note string attached to `entity`, or nil."
  [domain-id entity]
  (let [db (get-in @app-state [:domains domain-id :db])]
    (some-> db (d/datoms :eavt entity :note) first :v)))

(defn all-atom-idents
  "Sorted seq of every `:db/ident` keyword in this domain — useful for
  suggestions when picking type constructors."
  [domain-id]
  (let [db (get-in @app-state [:domains domain-id :db])]
    (when db
      (->> (d/datoms db :aevt :db/ident)
           (map :v)
           sort))))

(defn untyped-atoms
  "Atom idents that aren't already a constructor of any declared type in
  the domain — candidates to add when growing a type."
  [domain-id]
  (let [taken (->> (get-in @app-state [:domains domain-id :schema :types])
                   (mapcat :constructors)
                   (map keyword)
                   set)]
    (->> (all-atom-idents domain-id)
         (remove taken))))

(defn entities-with-notes
  "Return a sorted seq of entity keywords that have a non-empty :note."
  [domain-id]
  (let [db (get-in @app-state [:domains domain-id :db])]
    (when db
      (->> (d/datoms db :aevt :note)
           (keep (fn [d]
                   (let [ident (some-> db (d/datoms :eavt (:e d) :db/ident)
                                       first :v)]
                     (when (and ident (not (str/blank? (:v d))))
                       ident))))
           sort))))

(defn note-backlinks
  "Return a seq of [entity note-text] for every entity whose note contains
  a wikilink `[[target]]` pointing at the given entity. The textual scan
  uses a regex; case-sensitive, matches whole keyword name only."
  [domain-id target]
  (let [db (get-in @app-state [:domains domain-id :db])]
    (when (and db (keyword? target))
      (let [;; escape regex metacharacters in the target name
            esc (str/replace (clojure.core/name target)
                             #"[.*+?^${}()|\[\]\\]" "\\\\$0")
            re (re-pattern (str "\\[\\[" esc "\\]\\]"))]
        (->> (d/datoms db :aevt :note)
             (keep (fn [d]
                     (let [text (:v d)
                           from (some-> db (d/datoms :eavt (:e d) :db/ident)
                                        first :v)]
                       (when (and text from
                                  (not= from target)
                                  (re-find re text))
                         [from text]))))
             (sort-by first))))))

;; ---------------------------------------------------------------------------
;; Cross-domain recent-activity feed

(defn recent-events
  "Return the most recent `n` events across all domains, sorted by :at
  descending. Each item is the original event map augmented with
  :domain-id. Skips seeded events (those with :at = 0)."
  [n]
  (->> (for [[id d] (:domains @app-state)
             evt (:events d)
             :when (pos? (:at evt 0))]
         (assoc evt :domain-id id))
       (sort-by :at >)
       (take n)))

;; ---------------------------------------------------------------------------
;; Provenance — derived from the event log

(defn triple-provenance
  "Where this triple originated: :event (asserted via UI / form), :imported
  (pulled from another domain), or :derived (came from a rule). Order of
  precedence: event > imported > derived."
  [domain-id triple]
  (let [tr (vec triple)
        evt? (some (fn [{:keys [op triple]}]
                     (and (= op :assert) (= (vec triple) tr)))
                   (get-in @app-state [:domains domain-id :events]))]
    (cond
      evt? :event
      :else :derived)))

;; ---------------------------------------------------------------------------
;; Query

(defn- parse-clauses
  "Parse the user's query body into a vector of Datalog clauses.

  Accepted body shapes:
    - One pattern:        [?a :parent ?b]
    - Several patterns:   [?a :parent ?b] [?b :age 70]
    - Full Datalog query: [:find ?a :where [?a :parent ?b]]

  We always wrap in `[…]` and read as EDN. If the result has `:find` at
  the top level we treat it as a full query; otherwise it's a vector of
  one or more clauses."
  [s]
  (let [s (str/trim (or s ""))
        parsed (reader/read-string (str "[" s "]"))
        full? (and (vector? parsed) (some #{:find} parsed))]
    (if full?
      {:full parsed}
      {:clauses (vec parsed)})))

(defn- free-vars
  "Walk a Datalog clause and collect the ?vars."
  [clauses]
  (let [acc (atom [])
        seen (atom #{})]
    (walk/postwalk
      (fn [x]
        (when (and (symbol? x)
                   (str/starts-with? (name x) "?")
                   (not (@seen x)))
          (swap! seen conj x)
          (swap! acc conj x))
        x)
      clauses)
    @acc))

(defn run-query
  "Run a saved query body against the domain's db. Rules are already
  materialised into the db at rebuild time, so this is plain Datalog —
  no `:in $ %` plumbing needed.

  Rule calls in the body — `(ancestor ?a :dave)` — are rewritten to
  attr patterns — `[?a :ancestor :dave]` — so users can keep the natural
  syntax.

  Returns {:vars [...] :rows [[...]]} or {:error msg}."
  [domain-id body-text]
  (try
    (let [domain (get-in @app-state [:domains domain-id])
          db (:db domain)
          {:keys [full clauses]} (parse-clauses body-text)
          q (or full
                (let [clauses' (mapv rewrite-rule-call clauses)
                      vars (free-vars clauses')]
                  (vec (concat [:find] vars [:where] clauses'))))
          rows (d/q q db)
          ;; resolve eids in rows to ident keywords where possible
          name-of (fn [v]
                    (if (number? v)
                      (or (:v (first (d/datoms db :eavt v :db/ident))) v)
                      v))
          rows (mapv (fn [row] (mapv name-of row)) rows)
          vars (or (some-> full
                           (->> (drop-while #(not= :find %))
                                rest
                                (take-while symbol?)))
                   (free-vars clauses))]
      {:vars (mapv #(subs (name %) 1) vars)
       :rows (vec rows)})
    (catch :default e
      (js/console.error "query failed" e)
      {:error (or (.-message e) (str e))})))
