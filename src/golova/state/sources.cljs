(ns golova.state.sources
  "External-source producers. A `source` is a declarative spec stored in
  `domains/<id>/sources.edn`; refreshing it fetches data (HTTP JSON,
  HTTP CSV, in-repo CSV), runs the mapping DSL to project records into
  triples, and emits those as events tagged `:source \"src/<name>\"`.

  Re-running a source is idempotent: each emitted event's :id is a
  deterministic hash of (source-name, entity-ident, attr, value), so
  re-asserting the same fact lands as the same event id and Datahike's
  :db.cardinality/one semantics make it a no-op. Removals are NOT
  detected in v1 — see comment on `refresh-source!`.

  Sources interact with the existing pipeline as ordinary event producers:
  the events flow through `rebuild/append-events!`, get persisted via the
  normal save path, and sync via git like any other event. The activity
  feed shows them with their `:source` tag for provenance."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [golova.csv :as csv]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.decl :as decl]
            [golova.state.rebuild :as rebuild]))

;; ===========================================================================
;; Mapping DSL
;; ===========================================================================
;;
;; Source spec shape:
;;
;;   {:name     "starred-repos"
;;    :kind     :http-json | :http-csv | :csv
;;    :url      "https://…"                     ; for :http-*
;;    :path     "data/team.csv"                  ; for :csv (path in repo)
;;    :array-at :results                          ; optional; for JSON whose array is nested
;;    :mapping
;;     {:each-as :item                            ; template var bound to each record
;;      :id-from :item.full_name                  ; → entity ident keyword (slugified)
;;      :triples [[:item :kind   :repo]           ; [LHS attr-keyword RHS]
;;                [:item :name   :item.name]      ; left=template var, attr=literal, right=path
;;                [:item :stars  :item.stargazers_count]]}}
;;
;; A term in a triple is resolved like this:
;;   - keyword that equals the :each-as var       → the entity ident for this record
;;   - keyword that starts with "<each-as>." (e.g. :item.foo.bar) → path-lookup in record
;;   - anything else                                → literal value (passed through as-is)

(defn path-lookup
  "Walk `path` (vector of string segments) into a CLJS record. Map keys are
  tried both as keywords and strings (so JSON-shaped or CSV-shaped data both
  work). Numeric segments index into vectors."
  [record path]
  (reduce
    (fn [val seg]
      (cond
        (nil? val) nil
        (map? val)
        (or (get val (keyword seg))
            (get val seg))
        (sequential? val)
        (let [idx (js/parseInt seg 10)]
          (when-not (js/isNaN idx) (nth val idx nil)))
        :else nil))
    record
    path))

(defn template-path
  "If `term` looks like a template-relative path keyword (e.g. :item.foo.bar
  when each-as is :item), return the path segments after the var. Otherwise nil."
  [each-as term]
  (when (and (keyword? term) each-as)
    (let [prefix (str (name each-as) ".")
          tn     (name term)]
      (when (str/starts-with? tn prefix)
        (vec (str/split (subs tn (count prefix)) #"\."))))))

(defn to-ident
  "Coerce a looked-up value into an entity ident (keyword). Strings get
  slugified so 'Alice Smith' / 'saviorand/golova-clj' become safe keywords."
  [v]
  (cond
    (keyword? v) v
    (string?  v) (keyword (core/slugify v))
    (number?  v) (keyword (str v))
    :else        (keyword (str (hash v)))))

(defn resolve-ident
  "Compute the entity ident for a single record per `:id-from`. The id-from
  is itself a term — usually a template path like :item.full_name."
  [each-as id-from record]
  (let [path (template-path each-as id-from)]
    (cond
      path                       (to-ident (path-lookup record path))
      (= each-as id-from)        nil                         ; not useful, but tolerate
      (keyword? id-from)         id-from                     ; literal ident
      :else                      (to-ident id-from))))

(defn resolve-term
  "Resolve one slot of a triple template (LHS / attr / RHS) against a record.
  `ident` is the entity ident for this record, pre-computed."
  [each-as ident record term]
  (let [path (template-path each-as term)]
    (cond
      path               (path-lookup record path)
      (= each-as term)   ident
      :else              term)))

(defn record->triples
  "Run the mapping over a single record. Returns a list of [e a v] triples
  with any triple containing nil dropped (a missing field shouldn't generate
  a fact at all)."
  [{:keys [each-as id-from triples] :or {each-as :item}} record]
  (let [ident (resolve-ident each-as id-from record)]
    (when ident
      (->> triples
           (map (fn [tpl]
                  (mapv #(resolve-term each-as ident record %) tpl)))
           (remove (fn [[_ _ v]] (nil? v)))
           vec))))

(defn records->triples
  "Run the mapping over a sequence of records, flattened. Pure."
  [mapping records]
  (vec (mapcat #(record->triples mapping %) records)))

;; ===========================================================================
;; Source-kind dispatch (returns Promise<records>)
;; ===========================================================================

(defmulti ^:private fetch-records :kind)

(defmethod fetch-records :http-json
  [{:keys [url array-at]}]
  (-> (js/fetch url)
      (.then (fn [^js r]
               (when-not (.-ok r)
                 (throw (ex-info (str "HTTP " (.-status r) " from " url) {:status (.-status r)})))
               (.text r)))
      (.then (fn [text]
               (let [parsed (js->clj (.parse js/JSON text) :keywordize-keys true)
                     records (if array-at (get parsed array-at) parsed)]
                 (when-not (sequential? records)
                   (throw (ex-info "JSON response is not an array (or :array-at didn't unwrap it)"
                                   {:got (type records)})))
                 (vec records))))))

(defn ^:private csv-text->records
  "Convert csv/parse output ({:headers :rows}) into a list of {:col val} maps."
  [{:keys [headers rows]}]
  (let [ks (mapv keyword headers)]
    (mapv (fn [row]
            (into {} (map vector ks row)))
          rows)))

(defmethod fetch-records :http-csv
  [{:keys [url]}]
  (-> (js/fetch url)
      (.then (fn [^js r]
               (when-not (.-ok r)
                 (throw (ex-info (str "HTTP " (.-status r) " from " url) {:status (.-status r)})))
               (.text r)))
      (.then (fn [text]
               (csv-text->records (csv/parse text))))))

(defmethod fetch-records :csv
  [{:keys [path]}]
  ;; In-repo CSV: pulled bytes live in :last-pulled-files (populated by
  ;; sync-pull!). Requires at least one sync before refresh works.
  (let [files (get-in @app-state [:sync-state :last-pulled-files])
        text  (get files path)]
    (if (nil? text)
      (js/Promise.reject
        (ex-info (str "CSV not found in last-pulled snapshot: " path
                      ". Sync first.")
                 {:path path}))
      (js/Promise.resolve (csv-text->records (csv/parse text))))))

(defmethod fetch-records :default [src]
  (js/Promise.reject
    (ex-info (str "unknown source :kind " (:kind src))
             {:source src})))

;; ===========================================================================
;; Event emission
;; ===========================================================================

(defn deterministic-source-event-id
  "Stable id derived from (source-name, entity, attr, value). Re-asserting
  the same fact through the same source produces the same event id, so
  refresh is idempotent."
  [source-name e a v]
  (let [h (hash [source-name e a v])]
    (str "src-" (.toString (bit-and h 0x7fffffff) 16))))

(defn triples->events
  "Convert triples to assert events with deterministic ids."
  [source-name triples]
  (mapv (fn [[e a v]]
          {:id     (deterministic-source-event-id source-name e a v)
           :op     :assert
           :triple [e a v]
           :at     0
           :source (str "src/" source-name)})
        triples))

;; ===========================================================================
;; Auto-declare predicates
;; ===========================================================================

(defn ^:private auto-declare-predicates!
  "Any attribute mentioned in `triples` that isn't already declared in
  the predicates schema gets a discovered-style entry added. Mirrors what
  the UI's 'discovered' badge would otherwise show, but materializes the
  declaration so the user can edit it.

  Inference: if every value for an attribute is a keyword, we treat both
  arg-types as 'atom'. If any value is a string, we treat the second
  arg-type as 'string'. If any value is a number, 'int'. (Loose but
  serviceable for v1.)"
  [domain triples]
  (let [existing (set (map :name (get-in @app-state [:schema :predicates])))
        by-attr  (group-by second triples)]
    (doseq [[attr ts] by-attr
            :let [aname (name attr)]
            :when (not (existing aname))]
      (let [vs (map #(nth % 2) ts)
            v-type (cond
                     (every? keyword? vs) "atom"
                     (some   string?  vs) "string"
                     (some   number?  vs) "int"
                     :else                "atom")]
        (decl/declare-predicate! domain aname ["atom" v-type])))))

;; ===========================================================================
;; Refresh — the public entry point
;; ===========================================================================

(defn ^:private mark-status!
  "Update per-source status on app-state without mutating the source decl
  itself. Status lives at [:sync-state :sources <name>]; transient."
  [src-name patch]
  (swap! app-state update-in [:sync-state :sources src-name]
         (fn [s] (merge (or s {}) patch))))

(defn ^:private find-source [src-name]
  (first (filter #(= src-name (:name %))
                 (get-in @app-state [:schema :sources]))))

(defn refresh-source!
  "Refresh one source by name. Returns a Promise resolving to
  {:emitted N :triples [...]}. On error rejects with ex-info.

  Idempotent: re-running produces the same event ids, so duplicate facts
  collapse. Does NOT detect removals — items that disappear from the
  remote remain asserted locally until a future version adds diff
  tracking."
  [src-name]
  (mark-status! src-name {:status :refreshing :error nil})
  (let [src (find-source src-name)]
    (if (nil? src)
      (let [e (ex-info (str "source not found: " src-name) {})]
        (mark-status! src-name {:status :error :error (.-message e)})
        (js/Promise.reject e))
      (-> (fetch-records src)
          (.then (fn [records]
                   (let [triples (records->triples (:mapping src) records)
                         events  (triples->events src-name triples)]
                     (auto-declare-predicates! (:domain src) triples)
                     (rebuild/append-events! events)
                     (core/save!)
                     (mark-status! src-name {:status :idle
                                             :last-fetched-at (.now js/Date)
                                             :last-record-count (count records)
                                             :last-triple-count (count triples)})
                     {:emitted (count events)
                      :triples triples})))
          (.catch (fn [^js e]
                    (mark-status! src-name {:status :error
                                            :error (or (.-message e) (str e))})
                    (throw e)))))))

(defn refresh-all-sources!
  "Refresh every source in the current schema, sequentially. Returns a
  Promise that resolves after all complete (one failure doesn't stop the
  rest; individual errors are recorded in per-source status)."
  []
  (let [names (map :name (get-in @app-state [:schema :sources]))]
    (reduce (fn [p src-name]
              (.then p (fn [_]
                         (.catch (refresh-source! src-name)
                                 (fn [_] nil)))))
            (js/Promise.resolve nil)
            names)))

;; ===========================================================================
;; Declaration CRUD (mirrors decl/declare-predicate! style)
;; ===========================================================================

(defn declare-source! [domain-id source]
  (let [src (assoc source :domain domain-id)]
    (swap! app-state update-in [:schema :sources]
           (fn [ss]
             (let [without (vec (remove #(and (= (:name src)   (:name %))
                                              (= (:domain src) (:domain %)))
                                        (or ss [])))]
               (conj without src))))
    (core/save!)))

(defn delete-source! [domain-id src-name]
  (swap! app-state update-in [:schema :sources]
         (fn [ss] (vec (remove #(and (= src-name  (:name %))
                                     (= domain-id (:domain %)))
                               (or ss [])))))
  (core/save!))

