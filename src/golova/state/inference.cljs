(ns golova.state.inference
  "scasp-clj integration: run deductive/inductive/abductive inference over
  the current DB, then store derived facts back as events.

  Translate flow:
    Golova triples ([e a v]) → scasp-clj rules/facts → run solver
    → results (variable bindings) → :assert events → rebuild!

  Rules authored in golova (Datalog format) stay in the Datalog materialiser.
  scasp-style rules are written separately as scasp programs in EDN and
  referenced by a :scasp-program key on a domain (future), or passed directly
  as a program map to run-inference!.

  Public API:
    (run-inference! program query mode opts)
      program  — scasp-clj program map (built with scasp.main/build-program)
      query    — seq of scasp goal terms, e.g. [{:op :flies :args [\"X\"]}]
      mode     — :deduction | :abduction (induction uses run-induction! separately)
      opts     — optional map: :max-results int, :var-names [\"X\" \"Y\" ...],
                               :predicate keyword, :domain keyword
      Runs solver, extracts bindings, stores derived triples as events.
      Returns {:results [...] :stored-count int}.

    (run-induction! ontology goal-kw opts)
      Runs FOLD-R, returns learned rules in scasp-clj format (does NOT auto-store).

    (triples->scasp-facts triples)
      Convert golova [e a v] triples to scasp-clj rule maps (arity-1 and arity-2).

    (current-db-as-facts)
      Snapshot current DB triples as scasp-clj facts."
  (:require [clojure.string :as str]
            [scasp.main    :as scasp]
            [scasp.program :as prog]
            [scasp.vars    :as vars]
            [scasp.inference :as inf]
            [scasp.fold    :as fold]
            [golova.state.core :as core]
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

  program   — scasp-clj program (from scasp.main/build-program)
              OR nil to build from current DB facts + user-supplied extra-rules
  query     — seq of scasp goal terms (the query)
  opts map keys:
    :extra-rules  seq of additional scasp rules to include alongside DB facts
    :mode         :deduction (default) | :abduction
    :max-results  max results to process (default 100)
    :var-names    seq of variable strings to extract (default: [\"X\"])
    :predicate    keyword attr to use when storing results (default: first query op)
    :domain       keyword to assign new atoms' :in-domain (optional)
    :abducibles   set of functor strings for abduction mode

  Returns {:results [...raw scasp results...] :triples [...] :stored-count int}."
  ([query] (run-inference! query {}))
  ([query opts]
   (let [mode         (get opts :mode :deduction)
         max-results  (get opts :max-results 100)
         var-names    (get opts :var-names ["X"])
         predicate    (or (get opts :predicate)
                          (when (seq query) (:op (first query))))
         domain       (get opts :domain)
         abducibles   (get opts :abducibles #{})
         extra-rules  (get opts :extra-rules [])

         ;; Build facts from current DB
         db-facts     (current-db-as-facts)
         all-rules    (into db-facts extra-rules)

         ;; Build and run the program
         program      (case mode
                        :abduction
                        (scasp/build-program all-rules query abducibles)
                        (scasp/build-program all-rules query))
         raw-results  (take max-results (scasp/solve all-rules query abducibles))

         ;; Extract triples from results
         derived-triples
         (->> raw-results
              (mapcat #(result->triples % var-names predicate))
              (remove nil?)
              distinct
              vec)

         ;; Check which triples are already in the DB (avoid duplicates)
         existing     (set (inspect/all-triples))
         new-triples  (remove #(contains? existing %) derived-triples)

         ;; Build events
         events       (mapv (fn [triple]
                              (let [evt (core/mk-event :assert {:triple (vec triple)} "scasp")]
                                (if domain
                                  (assoc evt :domain domain)
                                  evt)))
                            new-triples)]

     ;; Store :in-domain events for new keyword subjects if domain specified
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

     {:results     raw-results
      :triples     derived-triples
      :stored-count (count new-triples)})))

;; ---------------------------------------------------------------------------
;; Induction runner (does not auto-store — returns rules for user inspection)

(defn run-induction!
  "Run FOLD-R inductive learning.

  ontology  — seq of scasp-clj term/rule maps (background + pos/neg examples)
  goal-kw   — keyword for the target predicate (e.g. :likes)
  opts map keys:
    :max-results  max positive results to return (default: 20)

  Returns {:positive-rules [...] :exception-rules [...]} in scasp-clj format.
  These can be passed to scasp.main/solve-all for further reasoning."
  ([ontology goal-kw] (run-induction! ontology goal-kw {}))
  ([ontology goal-kw _opts]
   (inf/inference :induction ontology goal-kw)))

;; ---------------------------------------------------------------------------
;; Convenience: build a scasp program from golova's current DB

(defn db-program
  "Build a scasp-clj program from the current DB facts plus extra-rules,
  with the given query and optional abducibles."
  ([query] (db-program query [] #{}))
  ([query extra-rules] (db-program query extra-rules #{}))
  ([query extra-rules abducibles]
   (scasp/build-program (into (current-db-as-facts) extra-rules) query abducibles)))
