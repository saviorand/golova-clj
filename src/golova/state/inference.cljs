(ns golova.state.inference
  "scasp-clj integration: run deductive/inductive/abductive inference over
  the current DB, then store derived facts back as events.

  Translate flow:
    Golova triples ([e a v]) + domain Datalog rules
    → scasp-clj rules/facts → run solver
    → results (variable bindings) → :assert events → rebuild!

  Public API:
    (run-inference! query opts)
      query    — seq of scasp goal terms, e.g. [{:op :flies :args [\"X\"]}]
      opts     — optional map: :max-results int, :var-names [\"X\" \"Y\" ...],
                               :predicate keyword, :domain keyword,
                               :extra-rules seq, :abducibles set,
                               :mode :deduction (default) | :abduction
      Automatically includes the current domain's rules (converted from
      Golova Datalog format). Returns {:results [...] :triples [...] :stored-count int}.

    (run-induction! ontology goal-kw opts)
      Runs FOLD-R, returns learned rules in scasp-clj format (does NOT auto-store).

    (triples->scasp-facts triples)
      Convert golova [e a v] triples to scasp-clj rule maps.

    (current-db-as-facts)
      Snapshot current DB triples as scasp-clj facts.

    (domain-rules->scasp domain-id)
      Convert the current domain's Datalog rules to scasp-clj rule maps."
  (:require [clojure.string :as str]
            [scasp.main    :as scasp]
            [scasp.program :as prog]
            [scasp.vars    :as vars]
            [scasp.inference :as inf]
            [scasp.fold    :as fold]
            [golova.state.core :as core]
            [golova.state.domain :as domain]
            [golova.state.inspect :as inspect]
            [golova.state.rebuild :as rebuild]))

;; ---------------------------------------------------------------------------
;; Golova triple → scasp-clj fact translation

(defn- triple->scasp-rule
  "Convert one [e a v] triple to a scasp-clj rule (fact with empty body).
  Arity-2: (a e v), e.g. :likes :alice :bob → {:op :likes :args [:alice :bob]}
  Arity-1 (boolean): (a e) when v is true, e.g. :flies :alice true → {:op :flies :args [:alice]}"
  [[e a v]]
  (when (and (keyword? e) (keyword? a))
    (cond
      (= v true)     (prog/make-rule {:op a :args [e]} [])
      (keyword? v)   (prog/make-rule {:op a :args [e v]} [])
      (string? v)    nil  ; skip text attrs (notes etc.)
      (number? v)    (prog/make-rule {:op a :args [e v]} [])
      :else          nil)))

(defn triples->scasp-facts
  "Convert a seq of [e a v] triples to scasp-clj facts, filtering out
  non-translatable triples (string values, nil entities, etc.)."
  [triples]
  (->> triples
       (keep triple->scasp-rule)
       vec))

(defn current-db-as-facts
  "Return all current DB triples as scasp-clj facts."
  []
  (triples->scasp-facts (inspect/all-triples)))

;; ---------------------------------------------------------------------------
;; Golova Datalog rule → scasp-clj rule translation
;;
;; Golova rules are stored as Clojure vectors with Datalog syntax:
;;   [(head-sym ?var :atom ...) body-clause ...]
;;
;; Body clauses are either:
;;   [?e :attr ?v]           — triple pattern (vector)
;;   (:pred ?a ?b)           — goal call (list/seq, first is symbol or keyword)
;;   (not (:pred ?a))        — NAF (list starting with 'not)
;;
;; We convert to scasp-clj term maps:
;;   variables ?x → string "X" (uppercase, strip leading ?)
;;   atoms :foo   → keyword :foo
;;   numbers      → number
;;   predicates   → {:op :kw :args [...]}
;;   NAF          → {:op :not :args [inner]}

(defn- golova-var->scasp
  "Convert a Golova variable symbol like '?x to the scasp string var \"X\"."
  [sym]
  (let [n (name sym)
        stripped (if (str/starts-with? n "?") (subs n 1) n)]
    (str (str/upper-case (subs stripped 0 1))
         (subs stripped 1))))

(defn- golova-term->scasp
  "Convert a single Golova term (symbol var, keyword atom, or number) to scasp."
  [t]
  (cond
    (and (symbol? t) (str/starts-with? (name t) "?")) (golova-var->scasp t)
    (keyword? t) t
    (number? t)  t
    (symbol? t)  (keyword t)
    :else        nil))

(defn- golova-goal->scasp
  "Convert one Golova body clause to a scasp-clj goal term, or nil if unsupported."
  [clause]
  (cond
    ;; Triple pattern: [?e :attr ?v] or [?e :attr :val]
    (vector? clause)
    (let [[e a v] clause]
      (when (keyword? a)
        (let [e' (golova-term->scasp e)
              v' (golova-term->scasp v)]
          (when (and e' v')
            {:op a :args [e' v']}))))

    ;; NAF: (not (pred ...))
    (and (seq? clause) (= 'not (first clause)))
    (when-let [inner (golova-goal->scasp (second clause))]
      {:op :not :args [inner]})

    ;; Goal call: (pred ?a ?b ...) — first element is symbol or keyword
    (seq? clause)
    (let [[head & args] clause
          op (cond (symbol? head)  (keyword (name head))
                   (keyword? head) head
                   :else           nil)]
      (when op
        (let [converted (mapv golova-term->scasp args)]
          (when (every? some? converted)
            {:op op :args converted}))))

    :else nil))

(defn- golova-clause->scasp-rule
  "Convert one Golova rule clause vector to a scasp-clj rule map, or nil."
  [clause]
  (when (and (vector? clause) (seq? (first clause)))
    (let [[head-form & body-forms] clause
          [head-sym & head-args] head-form
          op  (cond (symbol? head-sym)  (keyword (name head-sym))
                    (keyword? head-sym) head-sym
                    :else               nil)]
      (when op
        (let [head-converted (mapv golova-term->scasp head-args)
              body-converted (keep golova-goal->scasp body-forms)]
          (when (every? some? head-converted)
            (prog/make-rule {:op op :args head-converted}
                            (vec body-converted))))))))

(defn domain-rules->scasp
  "Convert all Golova rules in domain-id to scasp-clj rule maps.
  Returns a vector of scasp rule maps."
  [domain-id]
  (when domain-id
    (->> (domain/rules-in domain-id)
         (keep (comp golova-clause->scasp-rule :clause))
         vec)))

;; ---------------------------------------------------------------------------
;; scasp result → golova triple extraction

(defn- result->triples
  "Extract derived triples from a single scasp result map.
  var-names: seq of query variable name strings (e.g. [\"X\" \"Y\"]).
  predicate: keyword for the attribute to use when storing arity-2 results.
  Returns a seq of [e a v] triples."
  [result var-names predicate]
  (let [bindings (scasp/result-bindings result var-names)
        filled   (mapv #(get bindings %) var-names)]
    (cond
      ;; arity-2: X binds to entity, Y binds to value → [X predicate Y]
      (and (= 2 (count var-names))
           (keyword? (first filled)))
      (when (some? (second filled))
        [[(first filled) predicate (second filled)]])

      ;; arity-1: X binds to entity → [X predicate true]
      (and (= 1 (count var-names))
           (keyword? (first filled)))
      [[(first filled) predicate true]]

      :else nil)))

;; ---------------------------------------------------------------------------
;; Main inference runner

(defn run-inference!
  "Run scasp inference and store derived facts as events.

  query     — seq of scasp goal terms (the query)
  opts map keys:
    :extra-rules  seq of additional scasp rules to include alongside DB facts
    :mode         :deduction (default) | :abduction
    :max-results  max results to process (default 100)
    :var-names    seq of variable strings to extract (default: [\"X\"])
    :predicate    keyword attr to use when storing results (default: first query op)
    :domain       keyword — domain whose rules to include (default: current domain)
                            pass nil to skip domain rules entirely
    :abducibles   set of functor strings for abduction mode (e.g. #{\"fly/1\"})

  Automatically converts the current domain's Datalog rules to scasp format
  and includes them in the program alongside DB facts.

  Returns {:results [...raw scasp results...] :triples [...] :stored-count int}."
  ([query] (run-inference! query {}))
  ([query opts]
   (let [max-results  (get opts :max-results 100)
         var-names    (get opts :var-names ["X"])
         predicate    (or (get opts :predicate)
                          (when (seq query) (:op (first query))))
         ;; :domain key controls which domain's rules to include.
         ;; :domain not set → use current domain. :domain nil → no domain rules.
         domain       (if (contains? opts :domain)
                        (get opts :domain)
                        (core/current-id))
         abducibles   (get opts :abducibles #{})
         extra-rules  (get opts :extra-rules [])

         ;; Build facts from current DB + converted domain rules
         db-facts      (current-db-as-facts)
         domain-rules  (if domain (domain-rules->scasp domain) [])
         all-rules     (-> db-facts
                           (into domain-rules)
                           (into extra-rules))

         ;; Run the solver
         raw-results   (take max-results (scasp/solve all-rules query abducibles))

         ;; Extract triples from results
         derived-triples
         (->> raw-results
              (mapcat #(result->triples % var-names predicate))
              (remove nil?)
              distinct
              vec)

         ;; Deduplicate against existing DB
         existing     (set (inspect/all-triples))
         new-triples  (remove #(contains? existing %) derived-triples)

         ;; Build events
         events       (mapv (fn [triple]
                              (let [evt (core/mk-event :assert {:triple (vec triple)} "scasp")]
                                (if domain
                                  (assoc evt :domain domain)
                                  evt)))
                            new-triples)]

     ;; Store events (with optional :in-domain for new atoms)
     (when (seq events)
       (let [all-evts
             (if domain
               (let [already-homed (into #{} (keep #(inspect/entity-domain (first %)) new-triples))]
                 (into events
                       (for [triple new-triples
                             :let [[e _ _] triple]
                             :when (and (keyword? e)
                                        (not (contains? already-homed e)))]
                         (core/mk-event :assert {:triple [e :in-domain domain]} "scasp-domain"))))
               events)]
         (rebuild/append-events! all-evts)))

     {:results      raw-results
      :triples      derived-triples
      :stored-count (count new-triples)})))

;; ---------------------------------------------------------------------------
;; Induction runner (does not auto-store — returns rules for user inspection)

(defn run-induction!
  "Run FOLD-R inductive learning.

  ontology  — seq of scasp-clj term/rule maps (background + pos/neg examples)
  goal-kw   — keyword for the target predicate (e.g. :likes)
  opts map keys:
    :max-results  max positive results to return (default: 20)

  Returns {:positive-rules [...] :exception-rules [...]} in scasp-clj format."
  ([ontology goal-kw] (run-induction! ontology goal-kw {}))
  ([ontology goal-kw _opts]
   (inf/inference :induction ontology goal-kw)))

;; ---------------------------------------------------------------------------
;; Convenience: build a scasp program from golova's current DB + domain rules

(defn db-program
  "Build a scasp-clj program from the current DB facts + domain rules + extra-rules."
  ([query] (db-program query [] #{}))
  ([query extra-rules] (db-program query extra-rules #{}))
  ([query extra-rules abducibles]
   (let [all (-> (current-db-as-facts)
                 (into (domain-rules->scasp (core/current-id)))
                 (into extra-rules))]
     (scasp/build-program all query abducibles))))
