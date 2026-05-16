(ns golova.state
  "Application state for Golova.

  Each domain is a self-contained Naga program (rules + axioms) with an
  event log of asserts/retracts and optional cross-domain imports.
  Schema metadata (declared types/predicates/saved queries) sits alongside
  the program text and drives the UI — it is not enforced by the engine.

  Persistence: the whole domain map plus device-id + UI selection is
  written through `storage/-save` on every state-changing operation.

  Adapted from spike/state.cljs (multi-domain rebuild + Pabu source rewriting)
  with the spike-specific UI fields removed and a UI/schema layer added."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [naga.lang.pabu :as pabu]
            [naga.rules :as rules]
            [naga.engine :as engine]
            [naga.store :as nstore]
            [naga.storage.datahike.core :as dh-store]
            [golova.storage :as storage]))

;; ---------------------------------------------------------------------------
;; State atom

(defonce app-state
  (r/atom
   {:device-id nil
    :backend nil
    :domains {}
    :current-domain nil
    :selection {:kind :scratch}
    :theme :light
    :expanded #{}
    :modal nil
    :top-query {:text "" :result nil}
    :last-saved nil
    :error nil
    :first-run? false}))

;; ---------------------------------------------------------------------------
;; Helpers

(defn current []
  (let [s @app-state]
    (get-in s [:domains (:current-domain s)])))

(defn current-id [] (:current-domain @app-state))
(defn device-id  [] (:device-id @app-state))

(defn- normalize-triple [t] (vec (take 3 t)))

;; ---------------------------------------------------------------------------
;; Namespacing — same scheme as spike. `family.parent(` ⇄ `family__parent(` ⇄
;; :family/parent.

(def ^:private dotted-pred-re
  #"([a-z][a-z0-9_]*)\.([a-z][a-z0-9_]*)\(")

(defn rewrite-source [src]
  (-> src
      ;; Tolerate Clojure-style `;;` comments (Pabu only knows `%`).
      (str/replace #"(?m);;.*$" "")
      (str/replace dotted-pred-re "$1__$2(")))

(defn rewrite-keyword [kw]
  (let [n (name kw)
        underscore-idx (str/index-of n "__")
        dot-idx (str/index-of n ".")]
    (cond
      (and underscore-idx (pos? underscore-idx))
      (keyword (subs n 0 underscore-idx) (subs n (+ underscore-idx 2)))

      (and dot-idx (pos? dot-idx) (nil? (namespace kw)))
      (keyword (subs n 0 dot-idx) (subs n (inc dot-idx)))

      :else kw)))

(defn- rewrite-pattern [p]
  (mapv (fn [x] (if (keyword? x) (rewrite-keyword x) x)) p))

(defn- rewrite-rule [rule]
  (-> rule
      (update :head (fn [hs] (mapv rewrite-pattern hs)))
      (update :body (fn [bs] (mapv #(if (vector? %) (rewrite-pattern %) %) bs)))))

(defn rewrite-parsed [{:keys [rules axioms]}]
  {:rules  (mapv rewrite-rule rules)
   :axioms (mapv rewrite-pattern axioms)})

;; ---------------------------------------------------------------------------
;; Events

(defn- mk-event
  ([op data] (mk-event op data nil))
  ([op data source]
   (merge {:id     (str (random-uuid))
           :device (device-id)
           :at     (.now js/Date)
           :op     op
           :source source}
          data)))

(defn- safe-name [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9_-]+" "_")
      (str/replace #"^_+|_+$" "")))

(defn- fresh-domain-id [label]
  (let [base (safe-name label)
        base (if (str/blank? base) "domain" base)
        taken (set (keys (:domains @app-state)))]
    (loop [id (keyword base) i 2]
      (if (taken id)
        (recur (keyword (str base "-" i)) (inc i))
        id))))

;; ---------------------------------------------------------------------------
;; Persistence

(defn serializable [state]
  {:device-id      (:device-id state)
   :current-domain (:current-domain state)
   :selection      (:selection state)
   :theme          (:theme state)
   :expanded       (vec (:expanded state))
   :domains        (into {}
                         (for [[id d] (:domains state)]
                           [id (select-keys d [:id :label :program-text
                                               :events :imports :schema])]))})

(defn save! []
  (when-let [b (:backend @app-state)]
    (storage/-save b (serializable @app-state))
    (swap! app-state assoc :last-saved (.now js/Date))))

;; ---------------------------------------------------------------------------
;; Empty / template domain

(def ^:private starter-program
  "% Welcome to Golova. This is a Naga program — Datalog-style.
% Facts assert ground triples; rules derive new triples from existing ones.
% Edit and press Cmd+Enter (or 'Rebuild') below.

parent(alice, bob).
parent(bob, carol).
parent(carol, dave).

ancestor(X, Y) :- parent(X, Y).
ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).
")

(defn- empty-domain [id label]
  {:id id
   :label label
   :program-text ""
   :events []
   :imports []
   :schema {:types [] :predicates [] :queries []}})

(defn- starter-domain [id label]
  (assoc (empty-domain id label) :program-text starter-program))

;; ---------------------------------------------------------------------------
;; Rebuild

(defn- apply-events [seed-axioms events]
  (reduce
   (fn [axs {:keys [op triple]}]
     (case op
       :assert      (conj axs (normalize-triple triple))
       :retract     (disj axs (normalize-triple triple))
       :set-program axs
       axs))
   (set (map normalize-triple seed-axioms))
   events))

(defn- effective-program-text [d]
  (or (some->> (:events d)
               (filter #(= :set-program (:op %)))
               last
               :program)
      (:program-text d)))

(defn- imported-axioms [all-domains imports]
  (for [{:keys [from predicates]} imports
        :let [src-store (get-in all-domains [from :store])
              from-ns (name from)]
        :when src-store
        :let [all-tr (try (nstore/resolve-pattern src-store '[?e ?a ?v])
                          (catch :default _ []))
              ok? (cond
                    (= predicates :all) (fn [[_ a _]] (and (keyword? a)
                                                            (nil? (namespace a))))
                    (set? predicates)   (fn [[_ a _]] (and (keyword? a)
                                                            (nil? (namespace a))
                                                            (contains? predicates a)))
                    :else               (constantly false))]
        [e a v] (filter ok? all-tr)]
    [e (keyword from-ns (name a)) v]))

(defn- rebuild-domain [domain all-domains]
  (try
    (let [prog-text (effective-program-text domain)
          parsed (if (str/blank? prog-text)
                   {:rules [] :axioms []}
                   (rewrite-parsed (pabu/read-str (rewrite-source prog-text))))
          {:keys [rules axioms]} parsed
          own-axioms (apply-events axioms (:events domain))
          imported (imported-axioms all-domains (:imports domain))
          final-axioms (into own-axioms (map normalize-triple imported))
          program (rules/create-program rules (vec final-axioms))
          store0 (dh-store/empty-store)
          [final-store stats _] (engine/run {:store store0} program)]
      (assoc domain
             :store final-store
             :rules rules
             :stats stats
             :asserted own-axioms
             :imported (set (map normalize-triple imported))
             :program-text prog-text
             :build-error nil))
    (catch :default e
      (js/console.error e)
      (assoc domain :build-error (or (.-message e) (str e))))))

(defn rebuild! []
  (let [pass (fn [doms]
               (reduce-kv (fn [acc id d] (assoc acc id (rebuild-domain d doms)))
                          doms doms))
        doms0 (:domains @app-state)
        doms1 (pass doms0)
        doms2 (pass doms1)]
    (swap! app-state assoc :domains doms2)
    (let [errors (->> (vals doms2) (keep :build-error))]
      (swap! app-state assoc :error (when (seq errors) (first errors))))))

;; ---------------------------------------------------------------------------
;; Bootstrap

(defn load-or-seed! [backend]
  (let [snap (storage/-load backend)]
    (swap! app-state assoc :backend backend)
    (if (and snap (seq (:domains snap)))
      (swap! app-state assoc
             :current-domain (:current-domain snap)
             :selection (or (:selection snap) {:kind :scratch})
             :theme (or (:theme snap) :light)
             :expanded (set (:expanded snap))
             :domains (:domains snap))
      (let [id :starter]
        (swap! app-state assoc
               :domains {id (starter-domain id "Starter")}
               :current-domain id
               :expanded #{id}
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
  (swap! app-state assoc :current-domain id :selection {:kind :scratch} :error nil)
  (save!))

(defn expand-domain! [id]
  (swap! app-state update :expanded (fnil conj #{}) id)
  (save!))

(defn set-theme! [t]
  (swap! app-state assoc :theme t)
  (save!))

(defn open-modal! [m] (swap! app-state assoc :modal m))
(defn close-modal! []  (swap! app-state assoc :modal nil))

;; ---------------------------------------------------------------------------
;; Domain CRUD

(defn create-domain! [label]
  (let [id (fresh-domain-id label)]
    (swap! app-state assoc-in [:domains id] (empty-domain id label))
    (swap! app-state assoc :current-domain id)
    (swap! app-state update :expanded conj id)
    (rebuild!)
    (save!)
    id))

(defn delete-domain! [id]
  (let [doms (dissoc (:domains @app-state) id)
        ;; remove dangling imports from other domains
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
;; Program / facts

(defn- append-event! [domain-id evt]
  (swap! app-state update-in [:domains domain-id :events] (fnil conj []) evt)
  (rebuild!)
  (save!))

(defn set-program! [domain-id text]
  (append-event! domain-id (mk-event :set-program {:program text})))

(defn assert-triple! [domain-id triple source]
  (append-event! domain-id
                 (mk-event :assert {:triple (normalize-triple triple)} source)))

(defn retract-triple! [domain-id triple]
  (append-event! domain-id (mk-event :retract {:triple (normalize-triple triple)})))

(defn replace-triple!
  "Retract `old` and assert `new` in a single rebuild pass."
  [domain-id old new]
  (swap! app-state update-in [:domains domain-id :events] (fnil into [])
         [(mk-event :retract {:triple (normalize-triple old)})
          (mk-event :assert  {:triple (normalize-triple new)} "edit")])
  (rebuild!)
  (save!))

;; ---------------------------------------------------------------------------
;; Form helpers (predicate add-row + inline cell edit).

(defn- type-decl [domain-id tname]
  (when tname
    (first (filter #(= tname (:name %))
                   (get-in @app-state [:domains domain-id :schema :types])))))

(defn declared-type?
  "True if `tname` is a user-declared enum type in this domain."
  [domain-id tname]
  (boolean (type-decl domain-id tname)))

(defn coerce-value
  "Parse a raw string from a form input into the value to store.
  `arg-type` is one of: \"int\", \"string\", \"atom\", or a declared type name.
  Returns the coerced value or throws on bad input."
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

      (= "string" arg-type)
      raw

      (or (= "atom" arg-type) (declared-type? domain-id arg-type))
      ;; atoms become bare keywords; namespaced if user wrote "ns/name"
      (let [s (str/replace raw #"^:" "")]
        (if-let [slash (str/index-of s "/")]
          (keyword (subs s 0 slash) (subs s (inc slash)))
          (keyword s)))

      :else
      raw)))

(defn extend-type!
  "Append a constructor to an existing type. Idempotent."
  [domain-id type-name ctor]
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
      (save!))))

(defn assert-from-form!
  "Assert a fact for a declared predicate by coercing raw form values to the
  predicate's declared types. Only supports arity-2 today (the storage model
  is triples — multi-arity needs reification). Throws on bad input."
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
;; Move declared item (type / predicate / query) to another domain.

(defn move-type! [src-id dst-id name]
  (when-let [t (first (filter #(= name (:name %))
                              (get-in @app-state [:domains src-id :schema :types])))]
    (swap! app-state update-in [:domains src-id :schema :types]
           (fn [ts] (vec (remove #(= name (:name %)) ts))))
    (swap! app-state update-in [:domains dst-id :schema :types]
           (fn [ts] (let [without (vec (remove #(= name (:name %)) (or ts [])))]
                      (conj without t))))
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
;; Reset / snapshot import.

(defn reset-all! []
  (when-let [b (:backend @app-state)]
    (storage/-clear b))
  (swap! app-state assoc
         :domains {}
         :current-domain nil
         :selection {:kind :scratch}
         :expanded #{}
         :error nil)
  (let [id :starter]
    (swap! app-state assoc
           :domains {id (starter-domain id "Starter")}
           :current-domain id
           :expanded #{id}))
  (rebuild!)
  (save!))

(defn import-snapshot!
  "Replace state from a parsed snapshot map (as produced by `serializable`)."
  [snap]
  (swap! app-state assoc
         :domains        (:domains snap)
         :current-domain (:current-domain snap)
         :selection      (or (:selection snap) {:kind :scratch})
         :theme          (or (:theme snap) (:theme @app-state))
         :expanded       (set (:expanded snap))
         :error          nil)
  (when (and (:current-domain @app-state)
             (not (contains? (:domains @app-state) (:current-domain @app-state))))
    (swap! app-state assoc :current-domain (first (keys (:domains @app-state)))))
  (rebuild!)
  (save!))

;; Parse a Pabu fragment like `parent(alice, bob).` into a triple set.
(defn parse-fragment [s]
  (try
    (let [{:keys [axioms]} (rewrite-parsed (pabu/read-str (rewrite-source s)))]
      [(mapv normalize-triple axioms) nil])
    (catch :default e
      [nil (or (.-message e) (str e))])))

(defn run-query
  "Run a Pabu-style query body against the current domain's store.
  Returns {:rows [[...]] :vars [v1 v2 ...]} or {:error msg}.

  Naga's pabu doesn't expose a query-parsing API directly, so the body is
  parsed as a one-line rule whose head returns the variables, evaluated
  by resolving each body pattern through the store and intersecting on
  shared variable bindings. Good enough for the simple cases the UI uses."
  [domain-id body-text]
  (try
    (let [t (str "_q(" (str/join ", " (or (some->> body-text
                                                   (re-seq #"\?[a-zA-Z][a-zA-Z0-9_]*")
                                                   distinct
                                                   sort)
                                          ["1"])) ")"
                 " :- " body-text ".")
          {:keys [rules]} (rewrite-parsed (pabu/read-str (rewrite-source t)))
          rule (first rules)
          store (get-in @app-state [:domains domain-id :store])
          ;; collect rule body patterns and run via naga's store
          patterns (->> (:body rule) (filter vector?))
          vars-in-head (->> (:head rule) first (filter symbol?))
          rows (loop [pats patterns
                      bindings [{}]]
                 (if (empty? pats)
                   bindings
                   (let [pat (first pats)
                         all (try (nstore/resolve-pattern store pat) (catch :default _ []))
                         next (for [b bindings
                                    triple all
                                    :let [b' (reduce
                                              (fn [acc [p v]]
                                                (cond
                                                  (reduced? acc) acc
                                                  (and (symbol? p) (str/starts-with? (name p) "?"))
                                                  (let [cur (get acc p ::none)]
                                                    (if (= cur ::none)
                                                      (assoc acc p v)
                                                      (if (= cur v) acc (reduced nil))))
                                                  (= p v) acc
                                                  :else (reduced nil)))
                                              b
                                              (map vector pat triple))]
                                    :when (and b' (not (reduced? b')))]
                                b')]
                     (recur (rest pats) (vec next)))))]
      {:vars (mapv #(subs (name %) 1) vars-in-head)
       :rows (mapv (fn [b] (mapv #(get b %) vars-in-head)) rows)})
    (catch :default e
      {:error (or (.-message e) (str e))})))

;; ---------------------------------------------------------------------------
;; Schema CRUD (UI metadata — types / predicates / saved queries per domain)

(defn declare-type! [domain-id name constructors]
  (let [name (str name)
        ctors (vec (remove str/blank? constructors))]
    (swap! app-state update-in [:domains domain-id :schema :types]
           (fn [ts]
             (let [without (vec (remove #(= name (:name %)) (or ts [])))]
               (conj without {:name name :constructors ctors})))))
  (save!))

(defn delete-type! [domain-id name]
  (swap! app-state update-in [:domains domain-id :schema :types]
         (fn [ts] (vec (remove #(= name (:name %)) (or ts [])))))
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
  (save!))

(defn delete-predicate! [domain-id name arity]
  (swap! app-state update-in [:domains domain-id :schema :predicates]
         (fn [ps] (vec (remove #(and (= name (:name %))
                                     (= arity (count (:argTypes %))))
                               (or ps [])))))
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

;; ---------------------------------------------------------------------------
;; Triples mentioning an entity (for the entity page)

(defn entity-mentions [domain-id e]
  (let [store (get-in @app-state [:domains domain-id :store])]
    (if-not store
      []
      (let [all (try (nstore/resolve-pattern store '[?e ?a ?v]) (catch :default _ []))]
        (filter (fn [[ee _ vv]] (or (= ee e) (= vv e))) all)))))

(defn all-triples [domain-id]
  (let [store (get-in @app-state [:domains domain-id :store])]
    (if store
      (try (nstore/resolve-pattern store '[?e ?a ?v]) (catch :default _ []))
      [])))
