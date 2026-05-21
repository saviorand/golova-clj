(ns golova.state.query
  "Run user-authored Datalog queries against the global db. Bare-clause
  shorthand is rewritten through `rebuild/rewrite-rule-call` so saved
  queries can use rule-call syntax."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.reader :as reader]
            [datahike.core :as d]
            [golova.state.core :refer [app-state]]
            [golova.state.rebuild :as rebuild]))

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
                (let [clauses' (mapv rebuild/rewrite-rule-call clauses)
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
