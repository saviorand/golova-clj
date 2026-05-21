(ns golova.state.convert
  "Schema-conversion ops: change a predicate's storage type after the
  fact (e.g. promote a free-text column to a ref / enum), drop a
  predicate and its facts, sweep zero-fact predicates. Also the
  declarative `apply-conversion-plan!` that runs a list of these as
  one operation."
  (:require [clojure.string :as str]
            [datahike.core :as d]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.domain :as domain]
            [golova.state.rebuild :as rebuild]))

;; ---------------------------------------------------------------------------
;; Predicate fact-count + cleanup

(defn predicate-fact-count
  "Number of distinct facts currently in the db under `attr`. Uses the
  :aevt index — :avet is only populated for ref / indexed / unique attrs,
  so plain string/long predicates would otherwise read as 0 and get
  swept up by `cleanup-empty-predicates!`."
  [attr]
  (let [db (:db @app-state)]
    (if-not db
      0
      (count (d/datoms db :aevt (keyword attr))))))

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
      (rebuild/rebuild!)
      (core/save!))
    {:dropped (vec @dropped)}))

;; ---------------------------------------------------------------------------
;; Convert a scalar predicate to refs

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
        raw-vals (->> (attr-values attr-kw)
                      (map (fn [v] (if (string? v) (str/trim v) v)))
                      (remove (fn [v] (or (nil? v)
                                          (and (string? v) (str/blank? v)))))
                      distinct
                      vec)
        slug-counts (volatile! {})
        kw-for (into {}
                     (for [v raw-vals
                           :let [base (core/slugify (if (string? v) v (str v)))
                                 base (if (str/blank? base) "value" base)
                                 n (get @slug-counts base 0)
                                 _ (vswap! slug-counts assoc base (inc n))
                                 final (if (zero? n) base (str base "-" (inc n)))]]
                       [v (keyword final)]))
        atom-events (vec
                      (mapcat (fn [[v vk]]
                                [(core/mk-event :assert
                                                {:triple [vk :in-domain target-domain]}
                                                "convert-refs")
                                 (core/mk-event :assert
                                                {:triple [vk :label
                                                           (if (string? v) v (str v))]}
                                                "convert-refs")])
                              kw-for))
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
        new-arg-type (or enum-type-name "atom")
        new-schema-preds (mapv (fn [p]
                                 (if (= attr-str (:name p))
                                   (assoc p :argTypes
                                          (vec (concat (butlast (:argTypes p))
                                                       [new-arg-type])))
                                   p))
                               (get-in @app-state [:schema :predicates]))
        new-schema-types (if enum-type-name
                           (let [existing (get-in @app-state [:schema :types])
                                 without (vec (remove #(= enum-type-name (:name %))
                                                      existing))]
                             (conj without
                                   {:name enum-type-name
                                    :constructors (mapv #(name (val %)) kw-for)
                                    ;; Enum type belongs in the domain
                                    ;; where its constructor atoms live —
                                    ;; not where the predicate is declared.
                                    :domain (or target-domain
                                                source-pred-domain)}))
                           (get-in @app-state [:schema :types]))]
    (swap! app-state
           (fn [s]
             (-> s
                 (assoc :events (into new-events atom-events))
                 (assoc-in [:schema :predicates] new-schema-preds)
                 (assoc-in [:schema :types] new-schema-types))))
    (rebuild/rebuild!)
    (core/save!)
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

;; ---------------------------------------------------------------------------
;; Drop predicate + its facts

(defn drop-predicate-with-facts!
  "Delete a predicate declaration AND retract every assertion under that
  attribute. Use for cleaning up imported predicates that have no data
  worth keeping."
  [pred-name arity]
  (let [attr (keyword pred-name)
        triples (->> (:events @app-state)
                     (filter (fn [e] (and (= :assert (:op e))
                                          (= attr (keyword (second (:triple e)))))))
                     (map :triple)
                     distinct)
        retract-evts (mapv #(core/mk-event :retract {:triple (vec %)}
                                           "drop-predicate") triples)]
    (swap! app-state update :events into retract-evts)
    (swap! app-state update-in [:schema :predicates]
           (fn [ps]
             (vec (remove #(and (= pred-name (:name %))
                                (= arity (count (:argTypes %)))) ps))))
    (rebuild/rebuild!)
    (core/save!)
    {:retracted (count retract-evts)}))

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
                       (string? to-domain) (domain/find-or-create-domain! to-domain)
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
