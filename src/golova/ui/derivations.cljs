(ns golova.ui.derivations
  "Functions that inspect the rule/predicate program: which rules use or
  define an attribute, predicate listing per domain, rule listing per
  domain, rule pretty-printer. Used by sidebar, palette, predicate-view,
  rule-view."
  (:require [clojure.string :as str]
            [golova.state :as state]
            [golova.ui.common :refer [fmt-val]]))

;; ---------------------------------------------------------------------------
;; Domain → declared/discovered predicate and rule listings

(defn domain-predicates
  "Seq of {:name :arity :facts [...] :declared?} for predicates visible
  under `domain-id`. Declared = filed under this domain; discovered =
  facts about atoms in this domain whose attr isn't declared here."
  [domain-id]
  (let [triples (state/triples-in-domain domain-id)
        own-attrs (->> triples (map second) (filter keyword?) distinct)
        declared-here (state/declared-predicates-in domain-id)
        declared-here-names (set (map :name declared-here))]
    (concat
     (for [p declared-here]
       {:name (:name p)
        :arity (count (:argTypes p))
        :arg-types (:argTypes p)
        :declared? true
        :facts (vec (filter #(= (keyword (:name p)) (second %)) triples))})
     (for [a own-attrs
           :when (not (contains? declared-here-names (name a)))]
       {:name (name a)
        :namespace (namespace a)
        :arity 2
        :declared? false
        :facts (vec (filter #(= a (second %)) triples))}))))

(defn domain-rules
  "Seq of {:name :arity :clauses [...]} for rules filed under `domain-id`,
  grouped by head name + arity."
  [domain-id]
  (->> (state/rules-in domain-id)
       (keep (fn [{:keys [clause]}]
               (when (and (vector? clause) (seq clause))
                 (let [head (first clause)]
                   (when (and (seq? head) (symbol? (first head)))
                     {:name (str (first head))
                      :arity (count (rest head))
                      :rule clause})))))
       (group-by (juxt :name :arity))
       (map (fn [[[nm ar] clauses]]
              {:name nm :arity ar :clauses (mapv :rule clauses)}))
       (sort-by :name)))

;; ---------------------------------------------------------------------------
;; Rule analysis — which rules use / define a given attribute

(defn rules-using-attr
  "Rules whose body references `attr` (as pattern `[?e attr ?v]` or call
  `(attr-name ?a ?b)`). `clauses` is a vec of bare rule clauses."
  [clauses attr]
  (let [attr-sym (symbol (clojure.core/name attr))]
    (->> clauses
         (filter
           (fn [rule]
             (let [body (rest rule)]
               (some (fn [c]
                       (cond
                         (and (vector? c) (>= (count c) 3)
                              (= attr (second c))) true
                         (and (seq? c) (symbol? (first c))
                              (= attr-sym (first c))) true
                         :else false))
                     body)))))))

(defn rules-defining-attr
  "Rules whose head produces facts under `attr`."
  [clauses attr]
  (let [attr-sym (symbol (clojure.core/name attr))]
    (->> clauses
         (filter
           (fn [rule]
             (let [head (first rule)]
               (and (seq? head) (symbol? (first head))
                    (= attr-sym (first head)))))))))

;; ---------------------------------------------------------------------------
;; Rule pretty-printing (Datahike rule shape)

(defn- fmt-rule-arg [v]
  (cond
    (symbol? v)  (str v)
    (keyword? v) (fmt-val v)
    (string? v)  (str "\"" v "\"")
    (vector? v)  (pr-str v)
    :else        (str v)))

(defn- fmt-head [head]
  (let [hname (first head)
        args (rest head)]
    (str hname "(" (str/join ", " (map fmt-rule-arg args)) ")")))

(defn- fmt-where-clause [c]
  (cond
    (vector? c)
    (let [[e a v & more] c]
      (if (and (keyword? a) (nil? more))
        (str (fmt-rule-arg e) " " (fmt-val a) " " (fmt-rule-arg v))
        (pr-str c)))
    (seq? c)
    (str (first c) "(" (str/join ", " (map fmt-rule-arg (rest c))) ")")
    :else (pr-str c)))

(defn rule-clause
  "Render a single rule clause `[(head args) body…]` as hiccup."
  [r]
  (let [head (first r)
        body (rest r)
        head-str (fmt-head head)
        body-str (str/join ",  " (map fmt-where-clause body))
        hl-args (fn [s]
                  (let [parts (re-seq #"\?[A-Za-z][A-Za-z0-9_]*|[^?]+|\?" s)]
                    (for [[i p] (map-indexed vector parts)]
                      (if (and (> (count p) 1) (= "?" (subs p 0 1)))
                        ^{:key i} [:span.var p]
                        ^{:key i} [:span p]))))]
    [:div.rule-clause
     [:div.rule-head (hl-args head-str)]
     [:div.rule-arrow ":-"]
     [:div.rule-body (hl-args body-str) [:span.dot "."]]]))
