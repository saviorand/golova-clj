(ns golova.state.facts
  "User-facing fact mutations: assert/retract triples, attach notes, and
  the form-driven assert pipeline (with per-arg-type coercion)."
  (:require [clojure.string :as str]
            [datahike.core :as d]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.rebuild :as rebuild]))

;; ---------------------------------------------------------------------------
;; Triple mutations

(defn assert-triple!
  "Assert a triple. Optional `:in-domain` will also auto-assert :in-domain
  for the subject if it doesn't already have one."
  ([triple source] (assert-triple! triple source nil))
  ([triple source owning-domain]
   (let [[e _ _] triple
         db (:db @app-state)
         already (when (and db owning-domain)
                   (some-> db (d/datoms :eavt e :in-domain) first :v))
         evts (cond-> [(core/mk-event :assert {:triple (vec triple)} source)]
                (and owning-domain (not already))
                (conj (core/mk-event :assert {:triple [e :in-domain owning-domain]}
                                     "auto-domain")))]
     (rebuild/append-events! evts))))

(defn retract-triple! [triple]
  (rebuild/append-event! (core/mk-event :retract {:triple (vec triple)})))

(defn replace-triple!
  "Retract `old` and assert `new` in a single rebuild."
  [old new]
  (rebuild/append-events!
    [(core/mk-event :retract {:triple (vec old)})
     (core/mk-event :assert {:triple (vec new)} "edit")]))

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
      (rebuild/rebuild!) (core/save!))))

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
