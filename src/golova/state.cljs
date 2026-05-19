(ns golova.state
  "Application state for Golova — unified single-Datahike-db model.

  All data lives in ONE :db. \"Domain\" is now a UI/organisational concept,
  not a separate database. Each domain is itself an atom in the db with
  :domain-label and (optionally) :domain-parent for nesting. Other atoms
  declare their primary domain via :in-domain.

  Predicates, types, rules, and saved queries are GLOBAL schema/program
  elements. Each declaration carries a `:domain` field that controls which
  sidebar group it appears under; the underlying schema entry is shared.

  Persistence: `serializable` stores program + events + UI metadata + the
  schema declarations. The :db is recomputed from this on load."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [datahike.core :as d]
            [golova.csv :as csv]
            [golova.storage :as storage]))

;; ---------------------------------------------------------------------------
;; State atom

(declare rebuild!)

(defonce app-state
  (r/atom
   {:device-id nil
    :backend nil
    ;; Data layer
    :db nil
    :db-schema nil
    :events []
    :rules []
    :schema {:types [] :predicates [] :queries []}
    :rejections []
    :build-error nil
    ;; UI state
    :current-domain nil           ; keyword, the focused domain atom
    :selection {:kind :home}
    :theme :light
    :expanded #{}                 ; set of expanded domain keywords
    :expanded-subs #{}            ; set of [domain-key sub-key] tuples
    :modal nil
    :popover nil
    :palette nil
    :top-query {:text "" :result nil}
    :home {:onboarding-collapsed? true}
    :last-saved nil
    :error nil
    :first-run? false}))

(defn current-id [] (:current-domain @app-state))
(defn device-id [] (:device-id @app-state))

;; ---------------------------------------------------------------------------
;; Schema

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

(defn- build-schema
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

;; ---------------------------------------------------------------------------
;; Tx helpers

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

;; --- Rule materialisation -------------------------------------------------

(defn- rewrite-rule-call
  "Sugar: rewrite `(head ?a ?b)` → `[?a :head ?b]` for arity-2 rule calls,
  and `(head ?a)` → `[?a :head true]` for arity-1 (matches the boolean flag
  the materializer emits for arity-1 rules). Plain patterns pass through."
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

;; --- Rebuild --------------------------------------------------------------

(defn- rebuild-state
  "Pure: given a snapshot of the data + program, return [db schema rejections error]."
  [state]
  (try
    (let [schema (build-schema state)
          db0 (d/empty-db schema {:schema-flexibility :write})
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
      [db3 schema (vec @rejections) nil])
    (catch :default e
      (js/console.error "rebuild failed:" (or (.-message e) (str e)))
      [nil nil [] (or (.-message e) (str e))])))

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

;; ---------------------------------------------------------------------------
;; Events / mutations

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

(defn- append-events! [evts]
  (swap! app-state update :events (fnil into []) evts)
  (rebuild!))

(defn- append-event! [evt] (append-events! [evt]))

;; ---------------------------------------------------------------------------
;; Persistence

(defn serializable [state]
  {:device-id (:device-id state)
   :version 2                                     ; schema/persistence shape version
   :current-domain (:current-domain state)
   :selection (:selection state)
   :theme (:theme state)
   :expanded (vec (:expanded state))
   :expanded-subs (vec (:expanded-subs state))
   :home (:home state)
   :rules (:rules state)
   :events (:events state)
   :schema (:schema state)})

(defn save! []
  (when-let [b (:backend @app-state)]
    (storage/-save b (serializable @app-state))
    (swap! app-state assoc :last-saved (.now js/Date))))

;; ---------------------------------------------------------------------------
;; Domain queries (read-only)

(defn domains-list
  "Sorted seq of domain entities in the current db: {:id :label :parent}.
  Top-level domains (no :domain-parent) come first."
  []
  (let [db (:db @app-state)]
    (if-not db
      []
      (let [ident-of (fn [eid]
                       (or (:v (first (d/datoms db :eavt eid :db/ident))) eid))]
        (->> (d/datoms db :aevt :domain-label)
             (mapv (fn [d]
                     (let [id (ident-of (:e d))
                           parent (some-> db (d/datoms :eavt (:e d) :domain-parent)
                                          first :v ident-of)]
                       {:id id
                        :label (:v d)
                        :parent parent})))
             (sort-by (juxt :parent :label)))))))

(defn domain-info
  "Lookup one domain's {:id :label :parent} or nil."
  [domain-id]
  (when domain-id
    (first (filter #(= domain-id (:id %)) (domains-list)))))

(defn atoms-in-domain
  "Sorted seq of atom keywords whose :in-domain is `domain-id`."
  [domain-id]
  (let [db (:db @app-state)]
    (when (and db domain-id)
      (let [dom-eid (some-> (d/datoms db :avet :db/ident domain-id) first :e)]
        (when dom-eid
          (->> (d/datoms db :avet :in-domain dom-eid)
               (keep (fn [d]
                       (some-> (d/datoms db :eavt (:e d) :db/ident) first :v)))
               sort))))))

(defn declared-types-in
  "User-declared types filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :types])))

(defn declared-predicates-in
  "User-declared predicates filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :predicates])))

(defn queries-in
  "Saved queries filed under domain `domain-id`."
  [domain-id]
  (filter #(= domain-id (:domain %)) (get-in @app-state [:schema :queries])))

(defn rules-in
  "Rules filed under domain `domain-id`. Rules have no per-clause metadata
  today, so for now we attach them to the :rules-domain field on each
  clause map. For backwards-compat we accept any clause and let the UI
  decide grouping; a simple model: store rules as
  [{:domain :d :clause [(head ?a) ...]}]."
  [domain-id]
  (filter #(= domain-id (:domain %)) (:rules @app-state)))

;; ---------------------------------------------------------------------------
;; Rules: representation
;;
;; Internally rules are stored in :rules as a vector of maps
;; {:id <uuid> :domain <kw-or-nil> :clause [(head ?a) [?a ...] ...]}.
;; Helpers below convert between the wrapped form (for sidebar grouping)
;; and the bare-clause form (what the materializer wants).

(defn- rule-clauses
  "Get bare rule clauses (vector of [(head) body...]) from current :rules."
  [state]
  (mapv :clause (:rules state)))

(defn- with-bare-rules
  "Temporarily replace :rules with bare clauses for the materializer.
  Returns the state with :rules as a vec of clause vectors."
  [state]
  (assoc state :rules (rule-clauses state)))

;; Override the public rebuild! to flatten :rules before passing to the
;; pure rebuilder, since materialize-rules expects bare clauses.
(defn rebuild!
  []
  (let [src @app-state
        flat (with-bare-rules src)
        [db schema rejections err] (rebuild-state flat)]
    (swap! app-state assoc
           :db db :db-schema schema
           :rejections rejections
           :build-error err
           :error err)))

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

(defn toggle-subsection! [domain-id sub-key]
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
;; Domain CRUD (domains are first-class atoms)

(defn- fresh-domain-id
  "Pick a unique keyword id for a new domain, derived from `label`."
  [label]
  (let [base (let [b (safe-name label)] (if (str/blank? b) "domain" b))
        taken (set (map :id (domains-list)))]
    (loop [id (keyword base) i 2]
      (if (taken id)
        (recur (keyword (str base "-" i)) (inc i))
        id))))

(defn create-domain!
  "Create a new domain atom with `label`. Optional `parent` makes it a
  subdomain. Returns the new domain's keyword id."
  ([label] (create-domain! label nil))
  ([label parent]
   (let [id (fresh-domain-id label)
         evts (cond-> [(mk-event :assert {:triple [id :domain-label label]} "create-domain")]
                parent (conj (mk-event :assert {:triple [id :domain-parent parent]}
                                       "create-domain")))]
     (append-events! evts)
     (swap! app-state assoc :current-domain id)
     (save!)
     id)))

(defn delete-domain!
  "Remove a domain atom + retract :in-domain for every atom that pointed at it.
  Does NOT delete the atoms themselves (they become domainless)."
  [id]
  (let [db (:db @app-state)
        dom-eid (some-> (d/datoms db :avet :db/ident id) first :e)]
    (when dom-eid
      (let [member-eids (mapv :e (d/datoms db :avet :in-domain dom-eid))
            member-idents (keep (fn [eid]
                                  (some-> db (d/datoms :eavt eid :db/ident) first :v))
                                member-eids)
            evts (concat
                   (for [m member-idents]
                     (mk-event :retract {:triple [m :in-domain id]}))
                   [(mk-event :retract {:triple [id :domain-label
                                                 (-> (domain-info id) :label)]})])]
        (append-events! evts))))
  (swap! app-state update :schema
         (fn [s]
           (-> s
               (update :types (fn [ts] (mapv #(if (= id (:domain %))
                                                 (dissoc % :domain) %) ts)))
               (update :predicates (fn [ps] (mapv #(if (= id (:domain %))
                                                     (dissoc % :domain) %) ps)))
               (update :queries (fn [qs] (mapv #(if (= id (:domain %))
                                                  (dissoc % :domain) %) qs))))))
  (when (= id (current-id))
    (swap! app-state assoc :current-domain
           (-> (domains-list) first :id)))
  (rebuild!)
  (save!))

(defn rename-domain! [id new-label]
  (let [old (some-> (domain-info id) :label)]
    (when old
      (append-events!
        [(mk-event :retract {:triple [id :domain-label old]})
         (mk-event :assert {:triple [id :domain-label new-label]} "rename-domain")]))))

;; ---------------------------------------------------------------------------
;; Facts (assert / retract via the global event log)

(defn assert-triple!
  "Assert a triple. Optional `:in-domain` will also auto-assert :in-domain
  for the subject if it doesn't already have one."
  ([triple source] (assert-triple! triple source nil))
  ([triple source owning-domain]
   (let [[e _ _] triple
         db (:db @app-state)
         already (when (and db owning-domain)
                   (some-> db (d/datoms :eavt e :in-domain) first :v))
         evts (cond-> [(mk-event :assert {:triple (vec triple)} source)]
                (and owning-domain (not already))
                (conj (mk-event :assert {:triple [e :in-domain owning-domain]}
                                "auto-domain")))]
     (append-events! evts))))

(defn retract-triple! [triple]
  (append-event! (mk-event :retract {:triple (vec triple)})))

(defn replace-triple!
  "Retract `old` and assert `new` in a single rebuild."
  [old new]
  (append-events!
    [(mk-event :retract {:triple (vec old)})
     (mk-event :assert {:triple (vec new)} "edit")]))

(defn set-note!
  "Set/clear the markdown note attached to an entity."
  [entity markdown]
  (let [trimmed (str/trim (or markdown ""))
        db (:db @app-state)
        existing (some-> db (d/datoms :eavt entity :note) first :v)]
    (cond
      (and (str/blank? trimmed) existing)
      (retract-triple! [entity :note existing])
      (str/blank? trimmed) nil
      existing (replace-triple! [entity :note existing] [entity :note trimmed])
      :else (assert-triple! [entity :note trimmed] "note"))))

;; ---------------------------------------------------------------------------
;; Form helpers

(defn- type-decl [tname]
  (when tname
    (first (filter #(= tname (:name %)) (get-in @app-state [:schema :types])))))

(defn declared-type? [tname] (boolean (type-decl tname)))

(defn coerce-value
  "Parse a raw form-input string into the stored value, per arg type."
  [arg-type raw]
  (let [raw (str/trim (or raw ""))]
    (cond
      (str/blank? raw) (throw (ex-info "empty value" {}))

      (= "int" arg-type)
      (let [n (js/parseFloat raw)]
        (when (or (js/isNaN n) (not (re-matches #"-?\d+(\.\d+)?" raw)))
          (throw (ex-info (str "not a number: " raw) {})))
        (if (re-matches #"-?\d+" raw) (js/parseInt raw 10) n))

      (= "string" arg-type) raw

      (or (= "atom" arg-type) (declared-type? arg-type))
      (let [s (str/replace raw #"^:" "")]
        (if-let [slash (str/index-of s "/")]
          (keyword (subs s 0 slash) (subs s (inc slash)))
          (keyword s))))))

(defn extend-type! [type-name ctor]
  (let [ctor (str/trim (str ctor))]
    (when (seq ctor)
      (swap! app-state update-in [:schema :types]
             (fn [ts]
               (mapv (fn [t]
                       (if (= type-name (:name t))
                         (update t :constructors
                                 (fn [cs] (vec (distinct (conj (or cs []) ctor)))))
                         t))
                     (or ts []))))
      (rebuild!) (save!))))

(defn assert-from-form!
  "Coerce form values per arg types, then assert. Arity-2 only.
  `owning-domain` (optional) auto-assigns :in-domain to the subject if new."
  ([pred-name arg-types raw-args]
   (assert-from-form! pred-name arg-types raw-args nil))
  ([pred-name arg-types raw-args owning-domain]
   (when (not= 2 (count arg-types))
     (throw (ex-info "only arity-2 is supported today" {:arity (count arg-types)})))
   (let [[t1 t2] arg-types
         [r1 r2] raw-args
         v1 (coerce-value t1 r1)
         v2 (coerce-value t2 r2)]
     (assert-triple! [v1 (keyword pred-name) v2] "form" owning-domain))))

;; ---------------------------------------------------------------------------
;; Schema declarations (UI metadata)
;; Each declaration carries a :domain field for sidebar placement.

(defn declare-type! [domain-id name constructors]
  (let [name (str name)
        ctors (vec (remove str/blank? constructors))]
    (swap! app-state update-in [:schema :types]
           (fn [ts]
             (let [without (vec (remove #(= name (:name %)) (or ts [])))]
               (conj without {:name name :constructors ctors :domain domain-id})))))
  (rebuild!) (save!))

(defn delete-type! [name]
  (swap! app-state update-in [:schema :types]
         (fn [ts] (vec (remove #(= name (:name %)) (or ts [])))))
  (rebuild!) (save!))

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
  (rebuild!) (save!))

(defn delete-predicate! [name arity]
  (swap! app-state update-in [:schema :predicates]
         (fn [ps] (vec (remove #(and (= name (:name %))
                                     (= arity (count (:argTypes %))))
                               (or ps [])))))
  (rebuild!) (save!))

(defn save-query! [domain-id name text]
  (let [name (str name)]
    (swap! app-state update-in [:schema :queries]
           (fn [qs]
             (let [without (vec (remove #(= name (:name %)) (or qs [])))]
               (conj without {:name name :text text :domain domain-id})))))
  (save!))

(defn delete-query! [name]
  (swap! app-state update-in [:schema :queries]
         (fn [qs] (vec (remove #(= name (:name %)) (or qs [])))))
  (save!))

(defn toggle-pin-query! [name]
  (swap! app-state update-in [:schema :queries]
         (fn [qs]
           (mapv (fn [q] (if (= name (:name q)) (update q :pinned? not) q))
                 (or qs []))))
  (save!))

(defn pinned-queries
  "Vec of {:domain :name :text} for pinned saved queries."
  []
  (vec (for [q (get-in @app-state [:schema :queries])
             :when (:pinned? q)]
         {:domain-id (:domain q) :name (:name q) :text (:text q)})))

;; ---------------------------------------------------------------------------
;; Rules CRUD (rules are filed by domain too)

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
  (rebuild!) (save!))

;; ---------------------------------------------------------------------------
;; Move between domains (just changes the :domain field on declarations)

(defn move-type! [type-name dst-id]
  (swap! app-state update-in [:schema :types]
         (fn [ts] (mapv (fn [t] (if (= type-name (:name t))
                                  (assoc t :domain dst-id) t)) ts)))
  (rebuild!) (save!))

(defn move-predicate! [name arity dst-id]
  (swap! app-state update-in [:schema :predicates]
         (fn [ps] (mapv (fn [p] (if (and (= name (:name p))
                                         (= arity (count (:argTypes p))))
                                  (assoc p :domain dst-id) p)) ps)))
  (rebuild!) (save!))

(defn move-query! [name dst-id]
  (swap! app-state update-in [:schema :queries]
         (fn [qs] (mapv (fn [q] (if (= name (:name q))
                                  (assoc q :domain dst-id) q)) qs)))
  (save!))

;; ---------------------------------------------------------------------------
;; Triple inspection

(defn all-triples
  "All user-visible datoms across the db as [e a v] vectors. The entity and
  any ref-valued objects render as their :db/ident keywords. Hides
  bookkeeping attrs (:db/ident itself, :domain-label, :in-domain,
  :domain-parent)."
  []
  (let [db (:db @app-state)
        schema (:db-schema @app-state)]
    (if-not db
      []
      (let [ident-of (fn [eid]
                       (or (:v (first (d/datoms db :eavt eid :db/ident))) eid))
            hidden? #{:db/ident :domain-label :in-domain :domain-parent :label}]
        (->> (d/datoms db :eavt)
             (remove #(hidden? (:a %)))
             (mapv (fn [d]
                     (let [v (:v d) a (:a d)
                           ref? (= :db.type/ref (get-in schema [a :db/valueType]))]
                       [(ident-of (:e d)) a (if ref? (ident-of v) v)]))))))))

(defn triples-in-domain
  "Filter `all-triples` to those whose subject is :in-domain `domain-id`."
  [domain-id]
  (let [members (set (atoms-in-domain domain-id))]
    (filterv (fn [[e _ _]] (contains? members e)) (all-triples))))

(defn entity-mentions [entity]
  (filterv (fn [[e _ v]] (or (= e entity) (= v entity))) (all-triples)))

(defn entity-note [entity]
  (let [db (:db @app-state)]
    (some-> db (d/datoms :eavt entity :note) first :v)))

(defn entity-domain
  "Return the :in-domain of an atom (its primary domain), or nil."
  [entity]
  (let [db (:db @app-state)]
    (when db
      (let [eid (some-> (d/datoms db :avet :db/ident entity) first :e)
            dom-eid (some-> db (d/datoms :eavt eid :in-domain) first :v)]
        (when dom-eid
          (some-> db (d/datoms :eavt dom-eid :db/ident) first :v))))))

(defn all-atom-idents []
  (let [db (:db @app-state)]
    (when db
      (->> (d/datoms db :aevt :db/ident) (map :v) sort))))

(defn untyped-atoms
  "Atom idents that aren't constructors of any declared type. (Optionally
  scoped to a domain.)"
  ([] (untyped-atoms nil))
  ([domain-id]
   (let [taken (->> (get-in @app-state [:schema :types])
                    (mapcat :constructors) (map keyword) set)
         pool (if domain-id (atoms-in-domain domain-id) (all-atom-idents))]
     (remove taken pool))))

(defn entities-with-notes
  "Atom keywords with non-empty :note. Optionally filtered to a domain."
  ([] (entities-with-notes nil))
  ([domain-id]
   (let [db (:db @app-state)]
     (when db
       (let [members (when domain-id (set (atoms-in-domain domain-id)))
             keep? (fn [k] (or (nil? members) (contains? members k)))]
         (->> (d/datoms db :aevt :note)
              (keep (fn [d]
                      (let [ident (some-> db (d/datoms :eavt (:e d) :db/ident)
                                          first :v)]
                        (when (and ident (not (str/blank? (:v d))) (keep? ident))
                          ident))))
              sort))))))

(defn note-backlinks
  "Seq of [from-atom note-text] where note contains a [[target]] wikilink."
  [target]
  (let [db (:db @app-state)]
    (when (and db (keyword? target))
      (let [esc (str/replace (clojure.core/name target)
                             #"[.*+?^${}()|\[\]\\]" "\\\\$0")
            re (re-pattern (str "\\[\\[" esc "\\]\\]"))]
        (->> (d/datoms db :aevt :note)
             (keep (fn [d]
                     (let [text (:v d)
                           from (some-> db (d/datoms :eavt (:e d) :db/ident)
                                        first :v)]
                       (when (and text from (not= from target) (re-find re text))
                         [from text]))))
             (sort-by first))))))

;; ---------------------------------------------------------------------------
;; Recent activity / provenance

(defn recent-events
  "Most recent `n` events sorted by :at desc. Skips seeded (:at 0)."
  [n]
  (->> (:events @app-state)
       (filter #(pos? (:at % 0)))
       (sort-by :at >)
       (take n)))

(defn triple-provenance [triple]
  (let [tr (vec triple)
        evt? (some (fn [{:keys [op triple]}]
                     (and (= op :assert) (= (vec triple) tr)))
                   (:events @app-state))]
    (if evt? :event :derived)))

;; ---------------------------------------------------------------------------
;; Query

(defn- parse-clauses [s]
  (let [s (str/trim (or s ""))
        parsed (reader/read-string (str "[" s "]"))
        full? (and (vector? parsed) (some #{:find} parsed))]
    (if full? {:full parsed} {:clauses (vec parsed)})))

(defn- free-vars [clauses]
  (let [acc (atom []) seen (atom #{})]
    (walk/postwalk
      (fn [x]
        (when (and (symbol? x) (str/starts-with? (name x) "?") (not (@seen x)))
          (swap! seen conj x) (swap! acc conj x))
        x)
      clauses)
    @acc))

(defn run-query
  "Run a saved query body against the global db.
  Returns {:vars [...] :rows [[...]]} or {:error msg}."
  [body-text]
  (try
    (let [db (:db @app-state)
          {:keys [full clauses]} (parse-clauses body-text)
          q (or full
                (let [clauses' (mapv rewrite-rule-call clauses)
                      vars (free-vars clauses')]
                  (vec (concat [:find] vars [:where] clauses'))))
          rows (d/q q db)
          name-of (fn [v]
                    (if (number? v)
                      (or (:v (first (d/datoms db :eavt v :db/ident))) v) v))
          rows (mapv (fn [row] (mapv name-of row)) rows)
          vars (or (some-> full
                           (->> (drop-while #(not= :find %)) rest
                                (take-while symbol?)))
                   (free-vars clauses))]
      {:vars (mapv #(subs (name %) 1) vars) :rows (vec rows)})
    (catch :default e
      (js/console.error "query failed" e)
      {:error (or (.-message e) (str e))})))

;; ---------------------------------------------------------------------------
;; CSV import

(defn- slugify [s]
  (-> (or s "") str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

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
                      {:name name :slug (slugify name) :type (col-type vals)
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
                     {:name name :slug (slugify name) :type (col-type vals)})
                   headers cols-vals)
        new-preds (vec (for [[i c] (map-indexed vector cols)
                             :when (not= i id-idx)]
                         {:name (:slug c) :argTypes ["atom" (:type c)]
                          :domain domain-id}))
        entity-keys (vec (distinct
                           (for [row rows
                                 :let [s (slugify (nth row id-idx ""))]
                                 :when (seq s)]
                             (keyword s))))
        membership-evts (vec (for [k entity-keys]
                               (mk-event :assert
                                         {:triple [k :in-domain domain-id]}
                                         "csv-import")))
        fact-evts (vec
                    (for [row rows
                          :let [id-raw (nth row id-idx "")
                                id-slug (slugify id-raw)]
                          :when (seq id-slug)
                          [i v] (map-indexed vector row)
                          :when (and (not= i id-idx) (not (str/blank? v)))
                          :let [col (nth cols i)
                                attr (keyword (:slug col))
                                val (if (= "int" (:type col))
                                      (js/parseInt (str/trim v) 10) v)]]
                      (mk-event :assert
                                {:triple [(keyword id-slug) attr val]}
                                "csv-import")))
        skipped (count (filter #(str/blank? (slugify (nth % id-idx ""))) rows))]
    (swap! app-state update-in [:schema :predicates]
           (fn [ps]
             (let [existing (set (map :name ps))]
               (into (vec ps) (remove #(existing (:name %)) new-preds)))))
    (append-events! (into membership-evts fact-evts))
    {:rows (count rows)
     :triples (count fact-evts)
     :predicates (count new-preds)
     :skipped-rows skipped}))

;; ---------------------------------------------------------------------------
;; Migration from old multi-db shape → new unified shape

(defn- old-shape? [snap]
  (and (map? snap) (contains? snap :domains) (nil? (:version snap))))

(defn- migrate-from-v1
  "Convert an old-shape snapshot (multi-domain) into the new unified shape.
  Strategy:
  - Each domain becomes an atom with :domain-label (a domain entity event)
  - Each event in domain D becomes a global event (subject stays as-is)
  - For each subject keyword, emit `:in-domain D` (first domain wins via
    cardinality :one ordering: assert in domain-iteration order, last
    assertion is the binding)
  - Predicate / type / query declarations from D get :domain D
  - Schema conflicts (same predicate name with different argTypes) — first
    wins, others are dropped with a console warning. (We don't rename in
    this first pass; can be improved later.)
  - Rule clauses from D become {:domain D :clause ...} entries

  Returns a v2-shape map ready to assoc into app-state."
  [snap]
  (let [domains (or (:domains snap) {})
        dom-ids (vec (keys domains))
        ;; produce events: first the domain-label setters, then per-domain
        ;; user events, then :in-domain assignments per subject.
        domain-events
        (vec (for [id dom-ids
                   :let [d (get domains id)
                         lbl (or (:label d) (name id))]]
               (mk-event :assert {:triple [id :domain-label lbl]}
                         "migration")))
        ;; copy user events as-is; tag them in chronological order
        user-events
        (vec (for [id dom-ids
                   evt (:events (get domains id))]
               evt))
        ;; collect (subject, domain) pairs to produce :in-domain assertions
        in-domain-events
        (let [seen (volatile! #{})
              out (volatile! [])]
          (doseq [id dom-ids
                  evt (:events (get domains id))
                  :let [[e _ _] (:triple evt)]
                  :when (and (keyword? e) (not (@seen e)))]
            (vswap! seen conj e)
            (vswap! out conj
                    (mk-event :assert {:triple [e :in-domain id]}
                              "migration")))
          @out)
        ;; merge schemas with conflict resolution (first wins by name+arity)
        merged-preds (let [out (volatile! [])
                           seen (volatile! #{})]
                       (doseq [id dom-ids
                               p (get-in domains [id :schema :predicates])
                               :let [k [(:name p) (count (:argTypes p))]]]
                         (if (@seen k)
                           (js/console.warn "migration: dropped duplicate predicate"
                                            (clj->js {:name (:name p)
                                                      :from id}))
                           (do (vswap! seen conj k)
                               (vswap! out conj (assoc p :domain id)))))
                       @out)
        merged-types (let [out (volatile! []) seen (volatile! #{})]
                       (doseq [id dom-ids
                               t (get-in domains [id :schema :types])
                               :when (not (@seen (:name t)))]
                         (vswap! seen conj (:name t))
                         (vswap! out conj (assoc t :domain id)))
                       @out)
        merged-queries (let [out (volatile! []) seen (volatile! #{})]
                         (doseq [id dom-ids
                                 q (get-in domains [id :schema :queries])
                                 :when (not (@seen (:name q)))]
                           (vswap! seen conj (:name q))
                           (vswap! out conj (assoc q :domain id)))
                         @out)
        merged-rules (vec (for [id dom-ids
                                clause (get-in domains [id :rules])]
                            {:id (str (random-uuid))
                             :domain id
                             :clause clause}))]
    {:version 2
     :device-id (:device-id snap)
     :current-domain (:current-domain snap)
     :selection (or (:selection snap) {:kind :home})
     :theme (or (:theme snap) :light)
     :expanded (vec (:expanded snap))
     :expanded-subs (vec (:expanded-subs snap))
     :home (merge {:onboarding-collapsed? true} (:home snap))
     :rules merged-rules
     :events (into (into domain-events in-domain-events) user-events)
     :schema {:types merged-types
              :predicates merged-preds
              :queries merged-queries}}))

;; ---------------------------------------------------------------------------
;; Bootstrap

(def ^:private starter-rules
  '[[(ancestor ?x ?y) [?x :parent ?y]]
    [(ancestor ?x ?z) [?x :parent ?y] (ancestor ?y ?z)]])

(defn- starter-snap
  "v2-shape snapshot for first-run users: a single 'Starter' domain with
  the classic ancestor example."
  []
  (let [mk (fn [op tr] {:id (str (random-uuid)) :at 0 :op op
                        :source "starter" :triple tr})]
    {:version 2
     :current-domain :starter
     :selection {:kind :home}
     :theme :light
     :expanded #{:starter}
     :expanded-subs #{}
     :home {:onboarding-collapsed? true}
     :rules (mapv (fn [c] {:id (str (random-uuid))
                           :domain :starter :clause c}) starter-rules)
     :events [(mk :assert [:starter :domain-label "Starter"])
              (mk :assert [:alice :in-domain :starter])
              (mk :assert [:bob   :in-domain :starter])
              (mk :assert [:carol :in-domain :starter])
              (mk :assert [:dave  :in-domain :starter])
              (mk :assert [:alice :parent :bob])
              (mk :assert [:alice :parent :carol])
              (mk :assert [:bob :parent :dave])]
     :schema {:types [{:name "person"
                       :constructors ["alice" "bob" "carol" "dave"]
                       :domain :starter}]
              :predicates [{:name "parent" :argTypes ["person" "person"]
                            :domain :starter}]
              :queries [{:name "alice's descendants"
                         :text "(ancestor :alice ?d)"
                         :domain :starter}
                        {:name "dave's ancestors"
                         :text "(ancestor ?a :dave)"
                         :domain :starter}]}}))

(defn- load-snap! [snap]
  (swap! app-state assoc
         :current-domain (:current-domain snap)
         :selection (or (:selection snap) {:kind :home})
         :theme (or (:theme snap) :light)
         :expanded (set (:expanded snap))
         :expanded-subs (set (map vec (:expanded-subs snap)))
         :home (merge {:onboarding-collapsed? true} (:home snap))
         :rules (vec (or (:rules snap) []))
         :events (vec (or (:events snap) []))
         :schema (or (:schema snap) {:types [] :predicates [] :queries []})))

(defn load-or-seed! [backend]
  (let [snap (storage/-load backend)]
    (swap! app-state assoc :backend backend)
    (cond
      ;; v2 shape — load directly
      (and snap (= 2 (:version snap)))
      (load-snap! snap)

      ;; v1 (old multi-domain) — migrate then load
      (old-shape? snap)
      (do (js/console.log "migrating localStorage from v1 → v2")
          (let [v2 (migrate-from-v1 snap)]
            (load-snap! v2)))

      ;; first run — seed starter
      :else
      (do (swap! app-state assoc :first-run? true)
          (load-snap! (starter-snap)))))
  (rebuild!)
  (save!))

;; ---------------------------------------------------------------------------
;; Reset / import

(defn reset-all! []
  (when-let [b (:backend @app-state)] (storage/-clear b))
  (swap! app-state assoc
         :db nil :db-schema nil
         :events [] :rules []
         :schema {:types [] :predicates [] :queries []}
         :rejections [] :build-error nil
         :current-domain nil
         :selection {:kind :home}
         :expanded #{} :expanded-subs #{}
         :top-query {:text "" :result nil}
         :modal nil :popover nil :palette nil
         :home {:onboarding-collapsed? true}
         :error nil)
  (save!))

;; ---------------------------------------------------------------------------
;; Type conversion: change a predicate's storage type after the fact.

(defn- attr-values
  "All non-keyword values asserted under `attr` in the event log (used when
  converting a scalar predicate to refs — we need the source strings/ints
  to derive new atom keywords)."
  [attr]
  (->> (:events @app-state)
       (filter (fn [e] (and (= :assert (:op e))
                            (= attr (keyword (second (:triple e))))
                            (not (keyword? (nth (:triple e) 2))))))
       (map (comp #(nth % 2) :triple))))

(defn- rewrite-event-triple
  "If event e is an :assert under attr with the given old-value, return a new
  event with the same id but the triple's value replaced with new-value.
  Otherwise return e unchanged."
  [attr old-val new-val e]
  (let [[s a v] (:triple e)]
    (if (and (= :assert (:op e)) (= attr (keyword a)) (= old-val v))
      (assoc e :triple [s a new-val])
      e)))

(defn convert-predicate-to-refs!
  "Convert a scalar predicate `attr` to a ref predicate. For each unique
  non-empty value `v`:
    - generate a slug keyword `vk` (suffix with a counter on collision)
    - create an atom event: [vk :in-domain target-domain]
    - create a :label event: [vk :label v]   (preserves the original string)
    - rewrite every old [e attr v] assertion to [e attr vk]
  Then update the predicate declaration's argTypes so the second arg is
  the new type name (or 'atom' if `enum-type-name` is nil).
  Returns {:converted N :atoms K}."
  [{:keys [attr target-domain enum-type-name source-pred-domain]}]
  (let [attr-kw (keyword attr)
        attr-str (name attr-kw)
        ;; collect unique non-empty source values
        raw-vals (->> (attr-values attr-kw)
                      (map (fn [v] (if (string? v) (str/trim v) v)))
                      (remove (fn [v] (or (nil? v)
                                          (and (string? v) (str/blank? v)))))
                      distinct
                      vec)
        ;; assign keyword per unique value (slug + counter on collision)
        slug-counts (volatile! {})
        kw-for (into {}
                     (for [v raw-vals
                           :let [base (slugify (if (string? v) v (str v)))
                                 base (if (str/blank? base) "value" base)
                                 n (get @slug-counts base 0)
                                 _ (vswap! slug-counts assoc base (inc n))
                                 final (if (zero? n) base (str base "-" (inc n)))]]
                       [v (keyword final)]))
        ;; new atom events: ident + in-domain + label
        atom-events (vec
                      (mapcat (fn [[v vk]]
                                [(mk-event :assert
                                           {:triple [vk :in-domain target-domain]}
                                           "convert-refs")
                                 (mk-event :assert
                                           {:triple [vk :label
                                                      (if (string? v) v (str v))]}
                                           "convert-refs")])
                              kw-for))
        ;; rewrite events
        old-events (:events @app-state)
        new-events (mapv (fn [e]
                           (let [[_ a v] (:triple e)]
                             (if (and (= :assert (:op e))
                                      (= attr-kw (keyword a))
                                      (contains? kw-for
                                                 (if (string? v) (str/trim v) v)))
                               (rewrite-event-triple attr-kw
                                                     (if (string? v) (str/trim v) v)
                                                     (get kw-for
                                                          (if (string? v) (str/trim v) v))
                                                     e)
                               e)))
                         old-events)
        ;; Update the predicate declaration's argTypes
        new-arg-type (or enum-type-name "atom")
        new-schema-preds (mapv (fn [p]
                                 (if (= attr-str (:name p))
                                   (assoc p :argTypes
                                          (vec (concat (butlast (:argTypes p))
                                                       [new-arg-type])))
                                   p))
                               (get-in @app-state [:schema :predicates]))
        ;; If we want an enum type around these new atoms, declare it
        new-schema-types (if enum-type-name
                           (let [existing (get-in @app-state [:schema :types])
                                 without (vec (remove #(= enum-type-name (:name %))
                                                      existing))]
                             (conj without
                                   {:name enum-type-name
                                    :constructors (mapv #(name (val %)) kw-for)
                                    :domain (or source-pred-domain
                                                target-domain)}))
                           (get-in @app-state [:schema :types]))]
    ;; Apply all changes + rebuild
    (swap! app-state
           (fn [s]
             (-> s
                 (assoc :events (into new-events atom-events))
                 (assoc-in [:schema :predicates] new-schema-preds)
                 (assoc-in [:schema :types] new-schema-types))))
    (rebuild!)
    (save!)
    {:converted (count raw-vals)
     :atoms (count kw-for)}))

(defn convert-predicate-to-enum!
  "Convert a scalar predicate to a same-domain enum (the predicate's
  existing :domain). Like convert-to-refs but atoms land in the same
  domain as the predicate. `type-name` is the new enum's name (e.g.
  'priority')."
  [{:keys [attr type-name]}]
  (let [pred (first (filter #(= (name attr) (:name %))
                            (get-in @app-state [:schema :predicates])))]
    (convert-predicate-to-refs!
      {:attr attr
       :target-domain (:domain pred)
       :enum-type-name type-name
       :source-pred-domain (:domain pred)})))

(defn drop-predicate-with-facts!
  "Delete a predicate declaration AND retract every assertion under that
  attribute. Use for cleaning up imported predicates that have no data
  worth keeping."
  [pred-name arity]
  (let [attr (keyword pred-name)
        ;; collect (s,a,v) tuples to retract
        triples (->> (:events @app-state)
                     (filter (fn [e] (and (= :assert (:op e))
                                          (= attr (keyword (second (:triple e)))))))
                     (map :triple)
                     distinct)
        retract-evts (mapv #(mk-event :retract {:triple (vec %)}
                                      "drop-predicate") triples)]
    (swap! app-state update :events into retract-evts)
    (swap! app-state update-in [:schema :predicates]
           (fn [ps]
             (vec (remove #(and (= pred-name (:name %))
                                (= arity (count (:argTypes %)))) ps))))
    (rebuild!)
    (save!)
    {:retracted (count retract-evts)}))

(defn find-or-create-domain!
  "Return the id of an existing domain matching `label`, or create one and
  return the new id. Match is exact label OR id-keyword derived from label."
  [label]
  (let [target-id (-> label safe-name keyword)
        existing (->> (domains-list)
                      (filter (fn [d] (or (= target-id (:id d))
                                          (= label (:label d)))))
                      first
                      :id)]
    (or existing (create-domain! label))))

;; ---------------------------------------------------------------------------
;; Declarative conversion plans

(defn apply-conversion-plan!
  "Execute a vector of conversion ops, in order. Each op is a map with :op.

  Supported ops:
    {:op :convert :attr <kw> :to-domain <\"NewLabel\"|:existing-kw|:same>
                  :enum <type-name-or-nil>}
    {:op :drop    :attr <kw>}            ; drop predicate + retract its facts
    {:op :drop-empty}                    ; drop all 0-fact predicates

  Resolved target-domain rules:
    string  → find-or-create-domain! with that label
    keyword → use as-is (must be an existing domain id)
    :same   → use the predicate's current :domain
    nil/missing → defaults to predicate's current :domain

  Returns a vec of per-op result maps for telemetry."
  [plan]
  (let [out (volatile! [])]
    (doseq [op plan]
      (case (:op op)
        :convert
        (let [{:keys [attr to-domain enum]} op
              pred (first (filter #(= (name attr) (:name %))
                                  (get-in @app-state [:schema :predicates])))
              src-dom (:domain pred)
              target (cond
                       (string? to-domain) (find-or-create-domain! to-domain)
                       (= :same to-domain) src-dom
                       (keyword? to-domain) to-domain
                       :else src-dom)
              r (convert-predicate-to-refs!
                  {:attr attr
                   :target-domain target
                   :enum-type-name (when enum (str enum))
                   :source-pred-domain src-dom})]
          (vswap! out conj (merge {:op :convert :attr attr :target target} r)))

        :drop
        (let [{:keys [attr]} op
              pred (first (filter #(= (name attr) (:name %))
                                  (get-in @app-state [:schema :predicates])))]
          (when pred
            (drop-predicate-with-facts! (:name pred) (count (:argTypes pred)))
            (vswap! out conj {:op :drop :attr attr})))

        :drop-empty
        (vswap! out conj (assoc (cleanup-empty-predicates!) :op :drop-empty))

        ;; unknown op
        (vswap! out conj {:op (:op op) :error "unknown op"})))
    @out))

;; ---------------------------------------------------------------------------
;; Program import (rules + queries as a single EDN payload)

(defn- resolve-domain
  "Resolve a domain reference from a plan/program file:
    string  → find-or-create by label
    keyword → assume it's an existing id
    nil     → fall back to current-domain"
  [d]
  (cond
    (string? d)  (find-or-create-domain! d)
    (keyword? d) d
    :else        (current-id)))

(defn apply-program!
  "Import a program file: a map with :rules and/or :queries vectors.

  Each rule:  {:clause [(head ?a ?b) body…] :domain <kw|str>}
  Each query: {:name \"…\" :text \"…\" :domain <kw|str> :pinned? <bool>}

  Rules are APPENDED (not replaced) to the existing rules vector, filed
  under the resolved domain. Queries are upserted by :name within their
  domain (same as `save-query!`). One rebuild at the end."
  [{:keys [rules queries]}]
  (let [new-rules (mapv (fn [{:keys [clause domain]}]
                          {:id (str (random-uuid))
                           :domain (resolve-domain domain)
                           :clause clause})
                        (or rules []))]
    (swap! app-state update :rules (fnil into []) new-rules)
    (doseq [{:keys [name text domain pinned?]} (or queries [])]
      (let [dom (resolve-domain domain)]
        (swap! app-state update-in [:schema :queries]
               (fn [qs]
                 (let [without (vec (remove #(= name (:name %)) (or qs [])))]
                   (conj without
                         (cond-> {:name name :text text :domain dom}
                           pinned? (assoc :pinned? true))))))))
    (rebuild!)
    (save!)
    {:rules-added (count new-rules)
     :queries-added (count queries)}))

(defn predicate-fact-count
  "Number of distinct facts currently in the db under `attr`."
  [attr]
  (let [db (:db @app-state)]
    (if-not db
      0
      (count (d/datoms db :avet (keyword attr))))))

(defn cleanup-empty-predicates!
  "Drop every declared predicate whose attribute has zero facts in the db.
  Returns the names dropped."
  []
  (let [dropped (atom [])]
    (doseq [p (get-in @app-state [:schema :predicates])]
      (when (zero? (predicate-fact-count (:name p)))
        (swap! app-state update-in [:schema :predicates]
               (fn [ps] (vec (remove #(= (:name p) (:name %)) ps))))
        (swap! dropped conj (:name p))))
    (when (seq @dropped)
      (rebuild!)
      (save!))
    {:dropped (vec @dropped)}))

;; ---------------------------------------------------------------------------
;; Imports / snapshots

(defn import-snapshot! [snap]
  (let [v2 (cond
             (= 2 (:version snap)) snap
             (old-shape? snap) (migrate-from-v1 snap)
             :else (throw (ex-info "unrecognised snapshot shape" {})))]
    (load-snap! v2)
    (rebuild!)
    (save!)))
