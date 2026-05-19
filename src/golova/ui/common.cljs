(ns golova.ui.common
  "Low-level rendering helpers shared by every view: value formatting,
  atom + predicate links, markdown rendering, relative timestamps."
  (:require [clojure.string :as str]
            [golova.state :as state]))

;; ---------------------------------------------------------------------------
;; Value formatting

(defn fmt-val [v]
  (cond
    (keyword? v) (if-let [ns (namespace v)] (str ns "/" (name v)) (name v))
    (nil? v)     "·"
    (string? v)  (str "\"" v "\"")
    :else        (str v)))

(defn navigable? [v] (keyword? v))

(defn atom-link [v]
  (if (navigable? v)
    [:a.atom-link
     {:on-click #(do (.stopPropagation %)
                     (state/select! {:kind :entity :name v}))}
     (fmt-val v)]
    [:span (fmt-val v)]))

(defn pred-link
  "Clickable predicate name → navigates to its predicate page."
  ([attr] (pred-link attr 2))
  ([attr arity]
   (if (keyword? attr)
     [:a.atom-link.pred-link
      {:on-click #(do (.stopPropagation %)
                      (state/select! {:kind :predicate
                                      :name (name attr)
                                      :arity arity}))}
      (fmt-val attr)]
     [:span (str attr)])))

;; ---------------------------------------------------------------------------
;; Tiny markdown renderer.
;;   Supports: # / ## / ### headings, **bold**, *italic*, `code`, ``` fences,
;;   - and 1. lists, [text](url), [[wikilink]], paragraphs.

(defn- md-split
  "Walk a hiccup vector or raw string. String descendants get split by
  `re`, each match wrapped via `wrap`. Vector descendants are recursed
  into. Non-string atoms (keywords, numbers, etc.) pass through."
  [re wrap node]
  (cond
    (string? node)
    (let [chunks (atom [])
          last-idx (atom 0)]
      (doseq [m (re-seq re node)]
        (let [match (if (vector? m) (first m) m)
              idx (.indexOf node match @last-idx)]
          (when (> idx @last-idx)
            (swap! chunks conj (subs node @last-idx idx)))
          (swap! chunks conj (wrap m))
          (reset! last-idx (+ idx (count match)))))
      (when (< @last-idx (count node))
        (swap! chunks conj (subs node @last-idx)))
      @chunks)

    (vector? node)
    (let [tag (first node)
          props? (map? (second node))
          props (when props? (second node))
          children (drop (if props? 2 1) node)
          new-children (mapcat (fn [c]
                                 (let [r (md-split re wrap c)]
                                   (if (string? c) r [r])))
                               children)]
      (into (if props [tag props] [tag]) new-children))

    :else node))

(defn- md-inline [s]
  (-> [:span s]
      (->> (md-split #"`([^`]+)`"
                     (fn [m] [:code (second m)])))
      (->> (md-split #"\[\[([^\]]+)\]\]"
                     (fn [m]
                       (let [n (second m)]
                         [atom-link (keyword n)]))))
      (->> (md-split #"\[([^\]]+)\]\(([^)]+)\)"
                     (fn [m] [:a {:href (nth m 2) :target "_blank"} (nth m 1)])))
      (->> (md-split #"\*\*([^*]+)\*\*"
                     (fn [m] [:b (second m)])))
      (->> (md-split #"\*([^*]+)\*"
                     (fn [m] [:i (second m)])))))

(defn- md-block [block]
  (cond
    (re-find #"^```" block)
    [:pre.codefence [:code (str/join "\n"
                                      (->> (str/split block #"\n")
                                           (drop-while #(re-find #"^```" %))
                                           (take-while #(not (re-find #"^```" %)))))]]

    (re-find #"^### " block) [:h4 (md-inline (subs block 4))]
    (re-find #"^## "  block) [:h3 (md-inline (subs block 3))]
    (re-find #"^# "   block) [:h2 (md-inline (subs block 2))]

    (re-find #"^(- |\* )" (str/trim-newline block))
    (into [:ul]
          (for [line (str/split block #"\n")
                :let [m (re-find #"^(?:- |\* )(.+)" line)]
                :when m]
            [:li (md-inline (second m))]))

    (re-find #"^\d+\. " (str/trim-newline block))
    (into [:ol]
          (for [line (str/split block #"\n")
                :let [m (re-find #"^\d+\.\s+(.+)" line)]
                :when m]
            [:li (md-inline (second m))]))

    :else
    [:p (md-inline block)]))

(defn markdown
  "Render markdown text to hiccup. Splits on blank lines into blocks."
  [text]
  (when (and text (not (str/blank? text)))
    (into [:div.md]
          (for [b (str/split text #"\n\n+")
                :when (not (str/blank? b))]
            (md-block b)))))

;; ---------------------------------------------------------------------------
;; Relative-time formatting (used by activity feed)

(defn fmt-relative
  "Render a millisecond timestamp as 'just now', 'Nm ago', 'Nh ago',
  'Nd ago', or 'MMM D'."
  [ms]
  (let [now (.now js/Date)
        diff (max 0 (- now ms))
        s (quot diff 1000)]
    (cond
      (< s 45)     "just now"
      (< s 3600)   (str (max 1 (quot s 60)) "m ago")
      (< s 86400)  (str (quot s 3600) "h ago")
      (< s 604800) (str (quot s 86400) "d ago")
      :else        (let [d (js/Date. ms)]
                     (.toLocaleDateString d "en-US"
                                          #js {:month "short" :day "numeric"})))))
