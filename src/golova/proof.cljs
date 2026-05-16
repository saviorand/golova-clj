(ns golova.proof
  "Given a triple and a rule set, find which rules could have derived it and
  with what supporting facts. Computed on demand — no instrumentation of the
  naga engine required."
  (:require [datahike.api :as d]))

(defn- var? [x] (symbol? x))

(defn- unify
  "Unify a pattern [pe pa pv] against a concrete triple [te ta tv]. Returns
  a bindings map var->value, or nil if they don't unify."
  [[pe pa pv] [te ta tv]]
  (loop [pairs [[pe te] [pa ta] [pv tv]]
         bindings {}]
    (if-let [[[p t] & more] (seq pairs)]
      (cond
        (var? p)
        (cond
          (contains? bindings p) (if (= (bindings p) t)
                                   (recur more bindings)
                                   nil)
          :else                  (recur more (assoc bindings p t)))

        (= p t) (recur more bindings)
        :else   nil)
      bindings)))

(defn- substitute [pattern bindings]
  (mapv #(if (var? %) (get bindings % %) %) pattern))

(defn- pattern->where [t-sym [e a v]]
  [[t-sym :naga/e e]
   [t-sym :naga/a a]
   [t-sym :naga/v v]])

(defn- vars-in [patterns]
  (->> patterns
       (mapcat identity)
       (filter var?)
       distinct
       vec))

(defn- ground-pattern? [p]
  (every? (complement var?) p))

(defn explain-once
  "Return a sequence of derivations for `triple` by `rules` over `db`.
  Each derivation is {:rule-name str, :head-binding map, :body [concrete-triples]}.

  Returns at most `limit` derivations per rule (typically 1 — multiple bindings
  per rule mean multiple ways the fact could have been derived)."
  ([db rules triple] (explain-once db rules triple 1))
  ([db rules triple limit]
   (for [rule rules
         head-pattern (:head rule)
         :let [head-bindings (unify head-pattern triple)]
         :when head-bindings
         :let [body-substituted (mapv #(substitute % head-bindings) (:body rule))
               free-vars (vars-in body-substituted)
               where (vec (mapcat #(pattern->where (gensym "?t") %) body-substituted))
               rows (try
                      (if (seq free-vars)
                        (d/q {:find free-vars :where where} db)
                        (if (every? (fn [p]
                                      (seq
                                       (d/q {:find '[?t]
                                             :where (pattern->where '?t p)}
                                            db)))
                                    body-substituted)
                          #{[]}
                          #{}))
                      (catch :default _ #{}))]
         :when (seq rows)
         [idx row] (map-indexed vector (take limit (vec rows)))
         :let [body-bindings (merge head-bindings (zipmap free-vars row))
               supporting (mapv #(substitute % body-bindings) body-substituted)]]
     {:rule-name (:name rule)
      :head-binding head-bindings
      :body-binding body-bindings
      :supporting supporting})))

(defn asserted?
  "A triple is asserted if it's in the asserted set."
  [asserted triple]
  (contains? asserted (vec triple)))

(defn classify
  "Returns :asserted or :derived for a triple."
  [asserted triple]
  (if (asserted? asserted triple) :asserted :derived))
