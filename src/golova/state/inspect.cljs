(ns golova.state.inspect
  "Read-only views over the db + event log used by the facts/entity/rules
  UI: all-triples, mentions, backlinks, recent-events, provenance."
  (:require [clojure.string :as str]
            [datahike.core :as d]
            [golova.state.core :refer [app-state]]
            [golova.state.domain :as domain]))

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
            ;; Hide purely-organisational attrs from the user-facing facts
            ;; table; keep :label (it's the human-readable string for atoms
            ;; created by convert-to-refs and shows as their actual content).
            hidden? #{:db/ident :domain-label :in-domain :domain-parent}]
        (->> (d/datoms db :eavt)
             (remove #(hidden? (:a %)))
             (mapv (fn [d]
                     (let [v (:v d) a (:a d)
                           ref? (= :db.type/ref (get-in schema [a :db/valueType]))]
                       [(ident-of (:e d)) a (if ref? (ident-of v) v)]))))))))

(defn triples-in-domain
  "Filter `all-triples` to those whose subject is :in-domain `domain-id`."
  [domain-id]
  (let [members (set (domain/atoms-in-domain domain-id))]
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
         pool (if domain-id (domain/atoms-in-domain domain-id) (all-atom-idents))]
     (remove taken pool))))

(defn entities-with-notes
  "Atom keywords with non-empty :note. Optionally filtered to a domain."
  ([] (entities-with-notes nil))
  ([domain-id]
   (let [db (:db @app-state)]
     (when db
       (let [members (when domain-id (set (domain/atoms-in-domain domain-id)))
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
