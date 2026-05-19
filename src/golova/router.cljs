(ns golova.router
  "Hash-based router. Two-way syncs `window.location.hash` with
  app-state's `:selection`, so every view becomes a bookmarkable URL
  and browser back/forward works as expected.

  URL grammar (hashbang style, fits a static-served PWA):
    #/                                       → home
    #/<view>/…                               → view, domain resolved from
                                               the declaration's :domain
    #/d/<domain-id>/<view>/…                 → view scoped to a specific
                                               domain. Walks any number of
                                               domain segments before the
                                               view-kind to support sub-
                                               domain nesting:
                                               #/d/people/work/rules

  Views:
    rules                       → rules / facts overview
    type/<name>                 → type page
    predicate/<name>/<arity>    → predicate page
    rule/<name>                 → single-rule page
    query/<name>                → saved-query page (URL-encoded name)
    entity/<name>               → entity (atom) page
    domain                      → domain detail page (URL is
                                  #/d/<id>/domain)"
  (:require [clojure.string :as str]
            [golova.state :as state :refer [app-state]]))

(def ^:private syncing-from-url? (atom false))

(defn- enc [s] (js/encodeURIComponent (str s)))
(defn- dec-part [s] (js/decodeURIComponent (or s "")))

(def ^:private view-kinds
  "Path segments that mark the start of the view portion of the URL,
  after any leading /d/<id>/ chain of domain segments."
  #{"home" "rules" "type" "predicate" "rule" "query" "entity" "domain"})

(defn- selection->view-parts
  "Return the view portion of the URL (everything after any /d/<id>/ chain)
  as a vec of url-encoded segments, or nil for :home."
  [sel]
  (case (:kind sel)
    :home      nil
    :rules     ["rules"]
    :type      ["type" (enc (:name sel))]
    :predicate ["predicate" (enc (:name sel)) (str (or (:arity sel) 2))]
    :rule      ["rule" (enc (:name sel))]
    :query     ["query" (enc (:name sel))]
    :entity    ["entity"
                (let [n (:name sel)]
                  (enc (if (keyword? n) (name n) (str n))))]
    :domain    ["domain"]
    nil))

(defn- domain-path
  "Walk :domain-parent up to root and return the root-first chain, e.g.
  :work (with parent :people) → [:people :work]. Stops at unknown ids."
  [id]
  (let [by-id (into {} (map (juxt :id identity) (state/domains-list)))]
    (loop [cur id chain (list)]
      (if (or (nil? cur) (not (contains? by-id cur)))
        (vec chain)
        (let [parent (:parent (get by-id cur))]
          (recur parent (conj chain cur)))))))

(defn selection->hash [sel]
  (let [view (selection->view-parts sel)
        dom-chain (when-let [d (:domain sel)] (domain-path d))
        prefix (when (seq dom-chain)
                 (into ["d"] (map (fn [k] (enc (name k))) dom-chain)))
        parts (vec (concat prefix view))]
    (if (empty? parts) "#/" (str "#/" (str/join "/" parts)))))

(defn- parse-view
  "Parse the view portion of the path. `parts` is the segments after any
  /d/<id>/ chain; `domain` is the last resolved domain id or nil."
  [parts domain]
  (let [kind (first parts)
        with-d (fn [m] (cond-> m domain (assoc :domain domain)))]
    (case kind
      (nil "" "home") {:kind :home}
      "rules"   (with-d {:kind :rules})
      "type"    (with-d {:kind :type :name (dec-part (second parts))})
      "predicate"
      (with-d {:kind :predicate
               :name (dec-part (second parts))
               :arity (js/parseInt (or (nth parts 2 "2") "2") 10)})
      "rule"    (with-d {:kind :rule  :name (dec-part (second parts))})
      "query"   (with-d {:kind :query :name (dec-part (second parts))})
      "entity"  (with-d {:kind :entity :name (keyword (dec-part (second parts)))})
      "domain"  (with-d {:kind :domain})
      {:kind :home})))

(defn hash->selection [hash-str]
  (let [h (str/replace (or hash-str "") #"^#/?" "")
        parts (when (seq h) (str/split h #"/"))]
    (if (= "d" (first parts))
      (loop [chain [] rest-parts (rest parts)]
        (cond
          (empty? rest-parts)
          (parse-view [] (last chain))

          (contains? view-kinds (first rest-parts))
          (parse-view (vec rest-parts) (last chain))

          :else
          (recur (conj chain (keyword (dec-part (first rest-parts))))
                 (rest rest-parts))))
      (parse-view (vec parts) nil))))

(defn- apply-from-url!
  "Apply a URL-derived selection. When the selection carries an explicit
  :domain, also set :current-domain and expand that domain (and its
  ancestor chain) in the sidebar so direct URL navigation lands the
  user with the relevant tree visible."
  [sel]
  (let [dom (:domain sel)
        chain (when dom (domain-path dom))]
    (swap! app-state
           (fn [s]
             (cond-> (assoc s :selection sel)
               dom         (assoc :current-domain dom)
               (seq chain) (update :expanded (fnil into #{}) chain))))
    (state/save!)))

(defn install!
  "Wire two-way sync between :selection and window.location.hash."
  []
  ;; URL change → state
  (.addEventListener js/window "hashchange"
    (fn [_]
      (let [new-sel (hash->selection (.. js/window -location -hash))]
        (when (not= new-sel (:selection @app-state))
          (reset! syncing-from-url? true)
          (apply-from-url! new-sel)
          (reset! syncing-from-url? false)))))

  ;; State change → URL
  (add-watch app-state ::router
    (fn [_ _ old new]
      (when (and (not @syncing-from-url?)
                 (not= (:selection old) (:selection new)))
        (let [target (selection->hash (:selection new))
              current (.. js/window -location -hash)]
          (when (not= target current)
            (.replaceState js/history #js {} ""
                            (str (.. js/window -location -pathname) target)))))))

  ;; Initial alignment: if URL has a hash, use it; otherwise leave the
  ;; selection loaded from localStorage as-is.
  (let [initial-hash (.. js/window -location -hash)]
    (when (seq initial-hash)
      (let [sel (hash->selection initial-hash)]
        (when (not= sel (:selection @app-state))
          (reset! syncing-from-url? true)
          (apply-from-url! sel)
          (reset! syncing-from-url? false))))))
