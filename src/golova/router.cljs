(ns golova.router
  "Hash-based router. Two-way syncs `window.location.hash` with
  app-state's `:selection`, so every view becomes a bookmarkable URL
  and browser back/forward works as expected.

  URL grammar (hashbang style, fits a static-served PWA):
    #/                          → home
    #/rules                     → Rules / facts (current domain)
    #/type/<name>               → type page
    #/predicate/<name>/<arity>  → predicate page
    #/rule/<name>               → single-rule page
    #/query/<name>              → saved-query page (URL-encoded name)
    #/entity/<name>             → entity (atom) page

  current-domain is intentionally NOT in the URL — it's UI focus state
  the sidebar drives, not a per-view scope."
  (:require [clojure.string :as str]
            [golova.state :refer [app-state]]))

(def ^:private syncing-from-url? (atom false))

(defn- enc [s] (js/encodeURIComponent (str s)))
(defn- dec-part [s] (js/decodeURIComponent (or s "")))

(defn selection->hash [sel]
  (case (:kind sel)
    :home      "#/"
    :rules     "#/rules"
    :type      (str "#/type/" (enc (:name sel)))
    :predicate (str "#/predicate/" (enc (:name sel)) "/" (or (:arity sel) 2))
    :rule      (str "#/rule/" (enc (:name sel)))
    :query     (str "#/query/" (enc (:name sel)))
    :entity    (let [n (:name sel)]
                 (str "#/entity/"
                      (if (keyword? n) (enc (name n)) (enc (str n)))))
    "#/"))

(defn hash->selection [hash-str]
  (let [h (str/replace (or hash-str "") #"^#/?" "")
        parts (when (seq h) (str/split h #"/"))
        kind (first parts)]
    (case kind
      nil      {:kind :home}
      ""       {:kind :home}
      "rules"  {:kind :rules}
      "type"   {:kind :type :name (dec-part (second parts))}
      "predicate"
      {:kind :predicate
       :name (dec-part (second parts))
       :arity (js/parseInt (or (nth parts 2 "2") "2") 10)}
      "rule"   {:kind :rule  :name (dec-part (second parts))}
      "query"  {:kind :query :name (dec-part (second parts))}
      "entity" {:kind :entity :name (keyword (dec-part (second parts)))}
      {:kind :home})))

(defn install!
  "Wire two-way sync between :selection and window.location.hash."
  []
  ;; URL change → state
  (.addEventListener js/window "hashchange"
    (fn [_]
      (let [new-sel (hash->selection (.. js/window -location -hash))]
        (when (not= new-sel (:selection @app-state))
          (reset! syncing-from-url? true)
          (swap! app-state assoc :selection new-sel)
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
          (swap! app-state assoc :selection sel)
          (reset! syncing-from-url? false))))))
