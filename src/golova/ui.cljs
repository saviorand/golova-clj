(ns golova.ui
  "Reagent views for Golova. Each top-level surface is a function returning
  hiccup; the active one is chosen by `:selection` in app-state."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [cljs.pprint]
            [reagent.core :as r]
            [datahike.core :as d]
            [golova.state :as state :refer [app-state]]
            [golova.storage :as storage]))

(def ^:private result-row-cap 200)

(declare atom-link rule-clause sort-indicator cycle-sort)

;; ---------------------------------------------------------------------------
;; Tiny markdown renderer.
;;   Supports: # / ## / ### headings, **bold**, *italic*, `code`, ``` fences,
;;   - and 1. lists, [text](url), [[wikilink]], paragraphs.

(defn- md-split
  "Walk a hiccup vector or raw string. String descendants get split by
  `re`, each match wrapped via `wrap`. Vector descendants are recursed
  into. Non-string atoms (keywords, numbers, etc.) pass through.
  Always returns a single value of the same shape category as the input."
  [re wrap node]
  (cond
    (string? node)
    ;; returns seq of chunks
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
    ;; returns a vector (same shape)
    (let [tag (first node)
          props? (map? (second node))
          props (when props? (second node))
          children (drop (if props? 2 1) node)
          ;; only string children produce seqs to be flattened; everything
          ;; else (vector, keyword, etc.) stays a single child.
          new-children (mapcat (fn [c]
                                 (let [r (md-split re wrap c)]
                                   (if (string? c) r [r])))
                               children)]
      (into (if props [tag props] [tag]) new-children))

    :else node))

(defn- md-inline
  "Inline markdown → hiccup. Order: code first (its contents are sealed),
  then wikilinks, then links, bold, italic."
  [s]
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
;; Formatting helpers

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
  "Clickable predicate name → navigates to its predicate page. `attr` is a
  keyword (the attribute name); `arity` defaults to 2."
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
;; Table toolbar (search + provenance filter + summary)

(def ^:private provenance-colors
  {:event    "prov-event"
   :derived  "prov-derived"
   :program  "prov-program"
   :imported "prov-imported"})

(def ^:private provenance-tooltips
  {:event    "Asserted via the UI or imported snapshot. Lives in the event log; can be retracted."
   :derived  "Materialised by a rule from other facts. Cannot be retracted directly — change the rule or its inputs."
   :program  "Came from the program text (set-rules! / scratch editor)."
   :imported "Imported from another domain via cross-domain imports."})

(defn- row-matches?
  "Substring case-insensitive match across stringified row cells."
  [query row]
  (or (str/blank? query)
      (let [q (str/lower-case query)
            stringy (str/lower-case
                     (str/join " "
                               (map (fn [v]
                                      (cond
                                        (keyword? v) (str (when (namespace v)
                                                           (str (namespace v) "/"))
                                                          (name v))
                                        (nil? v) ""
                                        :else (str v)))
                                    row)))]
        (str/includes? stringy q))))

(defn sort-key
  "Comparable sort key for a cell value. Numbers compare numerically;
  strings and keywords sort within their own bucket; nil sorts last. The
  rank prefix keeps cross-type compares from throwing on heterogeneous
  columns."
  [v]
  (cond
    (nil? v)     [9 ""]
    (number? v)  [0 v]
    (boolean? v) [1 (if v 1 0)]
    (keyword? v) [2 (str (namespace v) "/" (name v))]
    (string? v)  [3 v]
    :else        [3 (str v)]))

(defn table-toolbar
  "Renders a search input, provenance filter pills, and a row-count summary.
  `state-atom` holds {:query string :provs #{kw}}. `provs-present` is the
  set of provenance values seen in the unfiltered rows — only those show
  up as pills. `total` and `shown` drive the summary text."
  [{:keys [state-atom provs-present total shown]}]
  (let [st @state-atom]
    [:div.table-toolbar
     [:input.tt-search
      {:type "text"
       :placeholder "Search rows…"
       :value (:query st)
       :on-change #(swap! state-atom assoc :query (.. % -target -value))}]
     (when (seq provs-present)
       [:div.tt-provs
        (for [p [:event :derived :program :imported]
              :when (provs-present p)]
          ^{:key p}
          [:button.prov-toggle
           {:class (str (provenance-colors p)
                        (when (contains? (:provs st) p) " on"))
            :title (str "Filter to " (name p) " facts only. "
                        (provenance-tooltips p))
            :on-click #(swap! state-atom update :provs
                              (fn [s] (let [s (or s #{})]
                                        (if (contains? s p) (disj s p) (conj s p)))))}
           (name p)])
        (when (seq (:provs st))
          [:button.prov-clear {:on-click #(swap! state-atom assoc :provs #{})}
           "clear"])])
     [:div.tt-summary
      shown " of " total
      (when (and (= shown 0) (pos? total))
        " · no matches")]]))

;; ---------------------------------------------------------------------------
;; Type-aware value display

(defn- constructor-type-map
  "Build a {constructor-name-str → type-name-str} lookup map for a domain.
  Called once per render at the view level, not per cell."
  [domain-id]
  (into {}
        (for [t (get-in @app-state [:domains domain-id :schema :types])
              c (:constructors t)]
          [c (:name t)])))

(defn type-value
  "Render a cell value with a type-aware visual treatment and a hover
  tooltip describing the value's runtime type. Cases:
  - declared enum constructor → colored pill labeled with the type name
  - integer / float / string / bool → subtle scalar pill
  - keyword (atom, no declared type) → plain atom-link wrapped so we can
    attach the tooltip
  `ctor-map` is the {name-str → type-name-str} lookup for the domain."
  [ctor-map v]
  (cond
    (and (keyword? v) (get ctor-map (name v)))
    (let [tn (get ctor-map (name v))]
      [:span.type-value {:class (str "type-" tn)
                         :title (str "constructor of type `" tn "` (ref)")}
       (atom-link v)])

    (boolean? v)
    [:span.type-value.type-scalar.type-bool   {:title "bool"} (str v)]
    (integer? v)
    [:span.type-value.type-scalar.type-int    {:title "int (long)"} (str v)]
    (number? v)
    [:span.type-value.type-scalar.type-float  {:title "float / double"} (str v)]
    (string? v)
    [:span.type-value.type-scalar.type-string {:title "string"} v]

    (keyword? v)
    [:span {:title "atom (ref) — untyped keyword"} (atom-link v)]

    :else (atom-link v)))

(defn chip-editor
  "Pill-based list editor. `state-atom` holds a vector of strings.
  Enter / comma commits the current input. Backspace on empty input
  deletes the last chip. If `suggestions-fn` is provided, it returns a
  seq of strings to offer (filtered by the current input)."
  [{:keys [state-atom placeholder suggestions-fn]}]
  (let [input (r/atom "")
        commit (fn [v]
                 (let [v (str/trim (or v ""))]
                   (when (and (seq v) (not (some #{v} @state-atom)))
                     (swap! state-atom conj v)
                     (reset! input ""))))]
    (fn [{:keys [placeholder suggestions-fn]}]
      (let [filtered-suggestions
            (when suggestions-fn
              (let [q (str/lower-case @input)
                    taken (set @state-atom)]
                (->> (suggestions-fn)
                     (map name)
                     (remove taken)
                     (filter #(str/includes? (str/lower-case %) q))
                     (take 12))))]
        [:div.chip-editor
         [:div.chip-list
          (for [[i it] (map-indexed vector @state-atom)]
            ^{:key (str i "-" it)}
            [:span.chip
             [:span.chip-text it]
             [:button.chip-x
              {:on-click #(swap! state-atom (fn [xs] (vec (remove #{it} xs))))
               :title "Remove"} "×"]])
          [:input.chip-input
           {:value @input
            :placeholder (or placeholder "Type and press Enter…")
            :on-change #(reset! input (.. % -target -value))
            :on-key-down (fn [e]
                           (cond
                             (or (= "Enter" (.-key e)) (= "," (.-key e)))
                             (do (.preventDefault e) (commit @input))
                             (and (= "Backspace" (.-key e))
                                  (str/blank? @input)
                                  (seq @state-atom))
                             (swap! state-atom (fn [xs] (vec (drop-last xs))))))}]]
         (when (seq filtered-suggestions)
           [:div.chip-suggestions
            [:span.cs-label "Suggest:"]
            (for [s filtered-suggestions]
              ^{:key s}
              [:button.chip.suggest
               {:on-click #(commit s)}
               "+ " s])])]))))

;; ---------------------------------------------------------------------------
;; Popover state. Anchored to a clicked element; rendered via the root.

(defn open-popover-from-event! [m e]
  (let [t (.-currentTarget e)
        r (.getBoundingClientRect t)]
    (swap! app-state assoc :popover
           (assoc m :anchor {:left (.-left r) :top (.-bottom r)
                             :right (.-right r) :width (.-width r)}))))

(defn close-popover! [] (swap! app-state assoc :popover nil))

(defn- popover-shell
  "Wraps a popover body in a fixed-position card anchored near `anchor`.
  Click-outside / Esc closes via the overlay layer at the root."
  [anchor & body]
  [:div.popover {:style {:left (str (max 8 (- (:left anchor) 0)) "px")
                         :top  (str (+ 6 (:top anchor)) "px")}}
   (into [:<>] body)])

;; ---------------------------------------------------------------------------
;; Typed input — used by the predicate add-row form AND inline cell-edit.

(defn- type-options
  "Constructor list for a declared type in the given domain, or nil."
  [domain-id tname]
  (let [t (first (filter #(= tname (:name %))
                         (get-in @app-state [:domains domain-id :schema :types])))]
    (when t (vec (:constructors t)))))

(defn typed-input
  "A controlled input for a single typed value. `state` is an r/atom holding
  {:val raw-string :new-mode? bool}. For declared enum types renders a
  <select> with a '+ new …' sentinel that swaps to a text input on click."
  [{:keys [domain-id arg-type state placeholder on-enter autofocus?]}]
  (let [opts (type-options domain-id arg-type)
        st @state
        in-new? (:new-mode? st)
        commit-new (fn [v]
                     (let [v (str/trim (or v ""))]
                       (when (seq v)
                         (state/extend-type! domain-id arg-type v)
                         (swap! state assoc :val v :new-mode? false))))]
    (cond
      ;; Declared enum: dropdown of constructors (+ "+ new")
      (and opts (not in-new?))
      [:select.typed
       {:value (or (:val st) "")
        :auto-focus autofocus?
        :on-change (fn [e]
                     (let [v (.. e -target -value)]
                       (if (= v "__new__")
                         (swap! state assoc :new-mode? true :val "")
                         (swap! state assoc :val v))))}
       [:option {:value ""} (str "— " arg-type " —")]
       (for [o opts] ^{:key o} [:option {:value o} o])
       [:option {:value "__new__"} "+ new …"]]

      ;; Declared enum but user picked "+ new" — show inline text input
      in-new?
      [:input.typed.new
       {:auto-focus true
        :placeholder (str "new " arg-type)
        :default-value (:val st)
        :on-key-down (fn [e]
                       (cond
                         (= "Enter" (.-key e))
                         (do (.preventDefault e)
                             (commit-new (.. e -target -value))
                             (when on-enter (on-enter)))
                         (= "Escape" (.-key e))
                         (swap! state assoc :new-mode? false :val "")))
        :on-blur #(commit-new (.. % -target -value))}]

      :else
      [:input.typed
       {:type (if (= "int" arg-type) "number" "text")
        :placeholder (or placeholder arg-type)
        :auto-focus autofocus?
        :value (or (:val st) "")
        :on-change #(swap! state assoc :val (.. % -target -value))
        :on-key-down (fn [e]
                       (when (and on-enter (= "Enter" (.-key e)))
                         (.preventDefault e)
                         (on-enter)))}])))

;; ---------------------------------------------------------------------------
;; Predicate / type / rule extraction from a domain

(defn- domain-predicates
  "Return seq of {:name k :arity n :facts [...]} for every user-visible
  attribute in the domain's store. Datahike-native: there are no arity-1
  type-tag tricks; everything is arity-2 EAV."
  [domain]
  (let [domain-id (:id domain)
        triples (state/all-triples domain-id)
        own-attrs (->> triples (map second) (filter keyword?) distinct)
        declared (set (map :name (get-in domain [:schema :predicates])))]
    (concat
     ;; declared predicates (always shown, even with zero facts)
     (for [p (get-in domain [:schema :predicates])]
       {:name (:name p)
        :arity (count (:argTypes p))
        :arg-types (:argTypes p)
        :declared? true
        :facts (vec (filter #(= (keyword (:name p)) (second %)) triples))})
     ;; discovered predicates (asserted via UI without prior declaration)
     (for [a own-attrs
           :when (not (contains? declared (name a)))]
       {:name (name a)
        :namespace (namespace a)
        :arity 2
        :declared? false
        :facts (vec (filter #(= a (second %)) triples))}))))

(defn- domain-rules
  "Return seq of {:name n :arity a :clauses [rule …]} per rule head.
  Datahike rules look like `[(head-name ?a ?b) body…]`; we group by head
  name + arity so multi-clause rules (e.g. ancestor base + recursive)
  appear as one sidebar entry."
  [domain]
  (->> (:rules domain)
       (keep (fn [r]
               (when (and (vector? r) (seq r))
                 (let [head (first r)]
                   (when (and (seq? head) (symbol? (first head)))
                     {:name (str (first head))
                      :arity (count (rest head))
                      :rule r})))))
       (group-by (juxt :name :arity))
       (map (fn [[[nm ar] clauses]]
              {:name nm :arity ar :clauses (mapv :rule clauses)}))
       (sort-by :name)))

;; ---------------------------------------------------------------------------
;; Topbar (query bar)

(defn topbar []
  (let [{:keys [top-query]} @app-state
        text (:text top-query)
        result (:result top-query)
        run! (fn []
               (let [t (str/trim text)]
                 (if (str/blank? t)
                   (swap! app-state assoc :top-query {:text "" :result nil})
                   (swap! app-state assoc-in [:top-query :result]
                          (state/run-query (state/current-id) t)))))]
    [:div
     [:div.topbar
      [:input.query
       {:placeholder "Ask. e.g. parent(?p, ?c)"
        :spellCheck "false"
        :value text
        :on-change #(swap! app-state assoc-in [:top-query :text] (.. % -target -value))
        :on-key-down (fn [e]
                       (when (= "Enter" (.-key e))
                         (.preventDefault e)
                         (run!)))}]
      [:button.primary {:on-click run!} "Run"]]
     (when result
       [:div.top-result
        (cond
          (:error result)
          [:div.results.err (:error result)]

          (empty? (:rows result))
          [:div.results
           [:div.summary "no solutions"]]

          :else
          (let [rows (:rows result)
                total (count rows)
                shown (take result-row-cap rows)]
            [:div.results
             [:div.summary total " solution" (when (not= 1 total) "s")
              (when (> total result-row-cap)
                [:span.trunc " · showing first " result-row-cap])]
             (for [[i row] (map-indexed vector shown)]
               ^{:key i}
               [:div.row
                (for [[k v] (map vector (:vars result) row)]
                  ^{:key k}
                  [:span.b [:span.k k] " = " [:span.v (atom-link v)]])])]))])]))

;; ---------------------------------------------------------------------------
;; Sidebar — domain tree

(defn- sub
  "Collapsible header row for a subsection within an expanded domain block.
  Caller renders the items only when `expanded?` is true; clicking the bar
  fires `on-toggle`. The `+` button (if `add-fn` given) does not toggle."
  [{:keys [label items add-fn expanded? on-toggle]}]
  (let [n (count items)]
    [:div.subsection {:class (str (when (zero? n) "empty ")
                                  (when expanded? "expanded"))
                      :on-click on-toggle}
     [:span.chev "▸"]
     [:span.lbl label]
     [:span.scount n]
     (when add-fn
       [:button.sub-add
        {:title (str "Add " label)
         :on-click (fn [e] (.stopPropagation e) (add-fn))} "+"])]))

(defn- nav-item [{:keys [active? icon icon-tooltip label meta on-click]}]
  [:div.nav-item {:class (when active? "active")
                  :on-click on-click}
   (if icon-tooltip
     [:span.icon {:title icon-tooltip} icon]
     [:span.icon icon])
   [:span.name.mono label]
   (when meta [:span.meta meta])])

(defn sidebar []
  (let [{:keys [domains current-domain selection expanded expanded-subs]} @app-state
        on-home? (= :home (:kind selection))
        sub-exp? (fn [id k] (contains? (or expanded-subs #{}) [id k]))]
    [:aside
     ;; brand
     [:div.brand
      [:span.logo "G"]
      [:span "Golova"]
      [:span.spacer]
      [:button.theme-toggle
       {:title "Toggle theme"
        :on-click #(state/set-theme! (if (= :dark (:theme @app-state)) :light :dark))}
       (if (= :dark (:theme @app-state)) "☾" "☼")]]

     ;; home link
     [:div.home-link {:class (when on-home? "active")
                      :on-click #(state/go-home!)}
      [:span.icon "🏠"]
      [:span.lbl "Home"]]

     ;; domains
     [:div.section
      [:h3 "Domains "
       [:span.count (count domains)]
       [:button.ghost.add-mini
        {:title "New domain"
         :on-click #(state/open-modal! {:kind :new-domain})}
        "+"]]
      (for [[id d] (sort-by (comp str first) domains)
            :let [exp? (contains? (or expanded #{}) id)
                  active-domain? (= id current-domain)
                  preds (domain-predicates d)
                  decl-pred (filter :declared? preds)
                  disc-pred (filter (complement :declared?) preds)
                  rules (domain-rules d)
                  types (get-in d [:schema :types])
                  queries (get-in d [:schema :queries])]]
        ^{:key (str "d-" (name id))}
        [:div.domain-block
         [:div.domain-header
          {:class (when exp? "expanded")
           :on-click (fn []
                       (if (= id current-domain)
                         (state/toggle-domain! id)
                         (do (state/switch-domain! id)
                             (state/expand-domain! id))))}
          [:span.chev "▸"]
          [:span.name (:label d)]
          [:button.row-menu
           {:title "Domain menu"
            :on-click (fn [e]
                        (.stopPropagation e)
                        (open-popover-from-event!
                         {:kind :domain-menu :domain id} e))}
           "⋯"]]
         (when exp?
           (let [all-preds (concat decl-pred disc-pred)
                 notes (state/entities-with-notes id)]
             [:div.domain-body
              (sub {:label "Types" :items types
                    :expanded? (sub-exp? id :types)
                    :on-toggle #(state/toggle-subsection! id :types)
                    :add-fn (fn []
                              (state/expand-subsection! id :types)
                              (state/open-modal! {:kind :new-type :domain id}))})
              (when (sub-exp? id :types)
                (for [t types]
                  ^{:key (str "t-" (:name t))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :type (:kind selection))
                                  (= (:name t) (:name selection)))
                    :icon "◆"
                    :label (:name t)
                    :meta (count (:constructors t))
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :type :name (:name t)}))}]))
              (sub {:label "Predicates" :items all-preds
                    :expanded? (sub-exp? id :predicates)
                    :on-toggle #(state/toggle-subsection! id :predicates)
                    :add-fn (fn []
                              (state/expand-subsection! id :predicates)
                              (state/open-modal! {:kind :new-predicate :domain id}))})
              (when (sub-exp? id :predicates)
                (for [p all-preds]
                  ^{:key (str "p-" (:name p) "/" (:arity p))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :predicate (:kind selection))
                                  (= (:name p) (:name selection))
                                  (= (:arity p) (:arity selection)))
                    :icon (if (:declared? p) "▦" "▢")
                    :icon-tooltip (if (:declared? p)
                                    "Declared — arg types are in this domain's schema."
                                    "Discovered — facts exist but no declared arg types.")
                    :label (str (:name p) "/" (:arity p))
                    :meta (count (:facts p))
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :predicate
                                                   :name (:name p)
                                                   :arity (:arity p)}))}]))
              (sub {:label "Rules" :items rules
                    :expanded? (sub-exp? id :rules)
                    :on-toggle #(state/toggle-subsection! id :rules)
                    :add-fn (fn []
                              (state/expand-subsection! id :rules)
                              (state/open-modal! {:kind :new-rule :domain id}))})
              (when (sub-exp? id :rules)
                (for [r rules]
                  ^{:key (str "r-" (:name r) "/" (:arity r))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :rule (:kind selection))
                                  (= (:name r) (:name selection)))
                    :icon "ƒ"
                    :label (str (:name r) "/" (:arity r))
                    :meta (when (> (count (:clauses r)) 1)
                            (count (:clauses r)))
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :rule :name (:name r)}))}]))
              (sub {:label "Queries" :items queries
                    :expanded? (sub-exp? id :queries)
                    :on-toggle #(state/toggle-subsection! id :queries)
                    :add-fn (fn []
                              (state/expand-subsection! id :queries)
                              (state/open-modal! {:kind :new-query :domain id}))})
              (when (sub-exp? id :queries)
                (for [q queries]
                  ^{:key (str "q-" (:name q))}
                  [nav-item
                   {:active? (and active-domain?
                                  (= :query (:kind selection))
                                  (= (:name q) (:name selection)))
                    :icon "?"
                    :label (:name q)
                    :on-click #(do (state/switch-domain! id)
                                   (state/select! {:kind :query :name (:name q)}))}]))
              (when (seq notes)
                [:<>
                 (sub {:label "Notes" :items notes
                       :expanded? (sub-exp? id :notes)
                       :on-toggle #(state/toggle-subsection! id :notes)})
                 (when (sub-exp? id :notes)
                   (for [n notes]
                     ^{:key (str "n-" (name n))}
                     [nav-item
                      {:active? (and active-domain?
                                     (= :entity (:kind selection))
                                     (= n (:name selection)))
                       :icon "✎"
                       :label (fmt-val n)
                       :on-click #(do (state/switch-domain! id)
                                      (state/select! {:kind :entity :name n}))}]))])
              [nav-item
               {:active? (and active-domain? (= :rules (:kind selection)))
                :icon "ƒ"
                :label "Rules / facts"
                :on-click #(do (state/switch-domain! id)
                               (state/select! {:kind :rules}))}]]))])]

     ;; footer
     [:div.sidebar-footer
      [:div.foot-row
       [:button.ghost
        {:title "Command palette (⌘K)"
         :on-click #(state/open-palette!)}
        "⌘ Search / commands"]]
      [:div.foot-row
       [:button.ghost
        {:title "Settings"
         :on-click #(state/open-modal! {:kind :settings})}
        "⚙ Settings"]]
      [:div.kshort [:span.lbl "Palette"] [:kbd "⌘K"]]
      [:div.kshort [:span.lbl "Rebuild"] [:kbd "⌘↵"]]]]))

;; ---------------------------------------------------------------------------
;; Home view — onboarding + domains overview

(defn- glossary []
  [:div.glossary
   [:div.gloss-item
    [:span.term "Atom"]
    [:span.def "A named thing — alice, sf, dune. Stored as a Datahike "
     [:code ":db/ident"] " so you can refer to it by keyword anywhere."]]
   [:div.gloss-item
    [:span.term "Predicate"]
    [:span.def "An attribute (a relation), like " [:code ":parent"] " or "
     [:code ":lives-in"] ". Each predicate is one Datahike schema entry. "
     [:b "▦ declared"] " = you set up the arg types; "
     [:b "▢ discovered"] " = the app inferred it from your data."]]
   [:div.gloss-item
    [:span.term "Rule"]
    [:span.def "A Datalog inference clause, like "
     [:code "[(ancestor ?a ?d) [?a :parent ?d]]"]
     ". Rules are run to fixed-point at every rebuild — their outputs "
     "are stored as real facts so queries don't need special syntax."]]
   [:div.gloss-item
    [:span.term "Fact provenance"]
    [:span.def
     [:span.pill.prov-event "event"] " — you asserted this via the UI or import. "
     [:span.pill.prov-derived "derived"] " — a rule produced this from other facts. "
     [:span.pill.prov-imported "imported"] " — pulled from another domain."]]
   [:div.gloss-item
    [:span.term "Domain"]
    [:span.def "A separate Datahike database with its own schema, facts, "
     "and rules. Use domains to scope things — e.g. \"books\", \"people\". "
     "Cross-domain imports let you reference facts from one in another."]]
   [:div.gloss-item
    [:span.term "Notes"]
    [:span.def "Any entity can have a markdown note. Use "
     [:code "[[wikilink]]"] " syntax inside a note to link to another atom."]]])

(defn home-view []
  (let [s @app-state
        collapsed? (get-in s [:home :onboarding-collapsed?])]
    [:div.view.home
     [:div.home-hero
      [:div.home-logo
       [:span.logo-big "G"]]
      [:div
       [:h1 "Golova"]
       [:p.tagline "A small, no-server PKM built on Datahike. "
        "Triples, rules, derivations — your knowledge as a graph."]]]

     [:div.section-card
      [:div.section-card-head
       [:h3 "Getting started"]
       [:button.ghost.small
        {:on-click #(state/toggle-onboarding!)}
        (if collapsed? "show" "hide")]]
      (when-not collapsed?
        [:div.section-card-body
         [:p "Golova represents your knowledge as " [:b "triples"] ": "
          [:code "[subject attribute value]"] ". The starter domain has "
          [:code "[alice :parent bob]"] " etc. — type facts in the predicate "
          "tables or via Cmd-K, define inference rules, and ask queries."]
         [glossary]
         [:p.hint "Quick keys: " [:kbd "⌘K"] " palette, "
          [:kbd "⌘↵"] " save rules, " [:kbd "Esc"] " close popovers."]])]

     [:div.section-card
      [:div.section-card-head
       [:h3 "Domains"]
       [:button.primary.small
        {:on-click #(state/open-modal! {:kind :new-domain})}
        "+ New"]]
      [:div.section-card-body
       [:div.domains-grid
        (for [[id d] (sort-by (comp str first) (:domains s))]
          (let [n-facts (count (state/all-triples id))
                n-preds (count (get-in d [:schema :predicates]))
                n-rules (count (:rules d))]
            ^{:key id}
            [:div.domain-card
             {:on-click #(state/switch-domain! id)}
             [:div.dc-name (:label d)]
             [:div.dc-meta
              [:span n-facts " facts"]
              [:span n-preds " preds"]
              [:span n-rules " rules"]]]))
        [:div.domain-card.new
         {:on-click #(state/open-modal! {:kind :new-domain})}
         [:div.dc-name "+ New domain"]
         [:div.dc-meta "Empty"]]]]]]))

;; ---------------------------------------------------------------------------
;; Rules editor

(defn- rules->text
  "Pretty-print a domain's rules vector as EDN, one rule per blank-separated
  block. Each rule is `[(head ?a ?b) [?a :attr ?b] …]`."
  [rules]
  (str/join "\n\n"
            (for [r (or rules [])]
              (with-out-str (cljs.pprint/pprint r)))))

(defn- text->rules
  "Parse the editor's text back into a rule vector. We wrap in [ ] and read
  as EDN so the user can write rules separated by whitespace."
  [text]
  (let [wrapped (str "[" text "]")]
    (vec (reader/read-string wrapped))))

(defn rules-view []
  (let [{:keys [current-domain]} @app-state
        d (state/current)
        initial (rules->text (:rules d))
        local (r/atom {:text initial :loaded current-domain
                       :saved? true :err nil})
        table-state (r/atom {:query "" :provs #{}
                             :sort {:col-cur nil :dir nil}})]
    (fn []
      (let [domain-id (state/current-id)
            d (state/current)
            current-text (rules->text (:rules d))
            err (:build-error d)]
        (when (not= domain-id (:loaded @local))
          (reset! local {:text current-text :loaded domain-id
                         :saved? true :err nil}))
        [:div.view
         [:div.view-head
          [:h2 "Rules"]
          [:span.desc "Datalog rules for "
           [:b (:label d)] ". Each rule is "
           [:code "[(head ?a ?b) body…]"]
           ". Edit and rebuild."]
          [:div.actions-right
           [:button.primary
            {:on-click (fn []
                         (try
                           (let [parsed (text->rules (:text @local))]
                             (state/set-rules! domain-id parsed)
                             (swap! local assoc :saved? true :err nil))
                           (catch :default e
                             (swap! local assoc :err (.-message e)))))}
            "Save rules"]]]
         [:div.program-editor {:class (cond (or err (:err @local)) "err"
                                            (not (:saved? @local)) "dirty")}
          [:textarea
           {:spellCheck "false"
            :value (:text @local)
            :on-change #(swap! local assoc :text (.. % -target -value)
                               :saved? false :err nil)
            :on-key-down (fn [e]
                           (when (and (or (.-metaKey e) (.-ctrlKey e))
                                      (= "Enter" (.-key e)))
                             (.preventDefault e)
                             (try
                               (let [parsed (text->rules (:text @local))]
                                 (state/set-rules! domain-id parsed)
                                 (swap! local assoc :saved? true :err nil))
                               (catch :default ex
                                 (swap! local assoc :err (.-message ex))))))}]
          [:div.footer
           [:span.status {:class (cond (or err (:err @local)) "err"
                                       (:saved? @local) "ok"
                                       :else "dirty")}
            (cond (or err (:err @local)) (str "error: " (or err (:err @local)))
                  (:saved? @local) "saved"
                  :else "edited — press Save (or ⌘↵)")]]]
         ;; Stored triples
         (when (:db d)
           (let [ctor-map (constructor-type-map domain-id)
                 triples (state/all-triples domain-id)
                 rows (mapv (fn [[e a v :as tr]]
                              {:e e :a a :v v :tr (vec tr)
                               :prov (state/triple-provenance domain-id tr)})
                            triples)
                 provs-present (set (map :prov rows))
                 {:keys [query provs sort]} @table-state
                 filtered (filterv #(row-matches? query [(:e %) (:a %) (:v %)]) rows)
                 filtered (if (seq provs)
                            (filterv #(contains? provs (:prov %)) filtered)
                            filtered)
                 filtered (let [{:keys [col-cur dir]} sort]
                            (if (and col-cur dir)
                              (let [k (case col-cur 0 :e 1 :a 2 :v 3 :prov nil)]
                                (if k
                                  (vec ((if (= dir :desc) reverse identity)
                                        (sort-by (comp sort-key k) filtered)))
                                  filtered))
                              filtered))
                 capped (take 200 filtered)
                 sort-cur (:col-cur sort)
                 sort-dir (:dir sort)
                 header (fn [i label]
                          [:th.sortable {:on-click #(cycle-sort table-state i)}
                           label
                           [:span.sort-arrow
                            (when (= i sort-cur) (sort-indicator sort-dir))]])]
             [:div {:style {:margin-top "24px"}}
              [:h3.materialized-h
               "Stored facts"
               [:span.count-hint (count rows) " total"
                (when (> (count filtered) 200) (str " · showing first 200 of "
                                                    (count filtered)))]
               (let [rj (:rejections d)]
                 (when (seq rj)
                   [:span.rejection-count
                    (str " · " (count rj) " rejected by schema")]))]
              (let [rj (:rejections d)]
                (when (seq rj)
                  [:div.rejection-warnings
                   (for [[i r] (map-indexed vector (take 5 rj))]
                     ^{:key i}
                     [:div.rej-item
                      [:span.rej-attr (str (or (:attribute r) ""))]
                      [:span.rej-msg (:message r)]])
                   (when (> (count rj) 5)
                     [:div.rej-more (str "+ " (- (count rj) 5) " more")])]))
              [table-toolbar
               {:state-atom table-state
                :provs-present provs-present
                :total (count rows)
                :shown (count filtered)}]
              [:table.facts
               [:thead [:tr
                        [header 0 "subject"]
                        [header 1 "predicate"]
                        [header 2 "object"]
                        [header 3 "source"]
                        [:th ""]]]
               [:tbody
                (for [{:keys [e a v tr prov]} capped]
                  (let [pred-si (state/attr-schema-info domain-id a)
                        pred-title (if pred-si
                                     (str (name a) " — "
                                          (state/db-type-label (:db/valueType pred-si))
                                          " · "
                                          (if (= :db.cardinality/many
                                                 (:db/cardinality pred-si))
                                            "many" "one"))
                                     (str (name a) " — no schema entry (discovered)"))]
                    ^{:key (pr-str tr)}
                    [:tr
                     [:td [:span {:title "entity (atom — :db/ident keyword)"}
                           (atom-link e)]]
                     [:td [:span {:title pred-title} [pred-link a]]]
                     [:td [type-value ctor-map v]]
                     [:td [:span.pill {:class (str "prov-" (name prov))
                                       :title (provenance-tooltips prov)}
                           (name prov)]]
                     [:td.delete
                      (when (= :event prov)
                        [:button.ghost.danger
                         {:title "Retract this fact"
                          :on-click #(state/retract-triple! domain-id (vec tr))}
                         "×"])]]))]]]))]))))

;; ---------------------------------------------------------------------------
;; Predicate table view

(defn- type-icon-for [t]
  (case t "int" "#" "string" "\"" "atom" "◇" "◆"))

(defn- guess-type
  "Heuristic arg type for an undeclared 2-arity predicate, picked from
  the current row's value. Falls back to \"atom\"."
  [v]
  (cond
    (integer? v) "int"
    (number?  v) "int"
    (string?  v) "string"
    (keyword? v) "atom"
    :else        "atom"))

(defn- pred-edit-row
  "Editable display row for one triple in the predicate table. Cells become
  inputs when clicked; Enter commits via replace-triple!, Esc cancels.
  Renders nothing if the row is in pure display mode (rare — we always
  render a row)."
  [domain-id arg-types triple]
  (let [tr (vec triple)
        [e a v] tr
        edit (r/atom nil)                              ;; nil | {:idx i :st {:val ...}}
        ctor-map (constructor-type-map domain-id)]
    (fn [domain-id arg-types triple]
      (let [tr (vec triple)
            [e a v] tr
            t1 (or (first arg-types)  (guess-type e))
            t2 (or (second arg-types) (guess-type v))
            cell (fn [idx orig arg-type]
                   (if (and @edit (= idx (:idx @edit)))
                     [:td.editing
                      [typed-input
                       {:domain-id domain-id
                        :arg-type  arg-type
                        :state     (r/cursor edit [:st])
                        :autofocus? true
                        :on-enter (fn []
                                    (try
                                      (let [coerced (state/coerce-value
                                                      domain-id arg-type
                                                      (:val (:st @edit)))
                                            new-tr (assoc tr idx coerced)]
                                        (state/replace-triple! domain-id tr new-tr)
                                        (reset! edit nil))
                                      (catch :default ex
                                        (swap! edit assoc :err (.-message ex)))))}]
                      [:button.ghost {:on-click #(reset! edit nil)
                                       :title "Cancel (Esc)"} "✕"]]
                     [:td.cellv
                      {:on-click #(reset! edit
                                          {:idx idx
                                           :st {:val (cond
                                                       (keyword? orig) (fmt-val orig)
                                                       (string? orig) orig
                                                       :else (str orig))}})
                       :title "Click to edit"}
                      [type-value ctor-map orig]]))]
        [:tr
         (cell 0 e t1)
         (cell 2 v t2)
         [:td.delete
          [:button.ghost.danger
           {:title "Retract"
            :on-click #(state/retract-triple! domain-id tr)}
           "×"]]]))))

(defn- pred-add-row
  "<tfoot> add-row form: one typed input per column, then Add."
  [domain-id pred-name arg-types]
  (let [n (count arg-types)
        cells (vec (repeatedly n #(r/atom {:val ""})))
        err (r/atom nil)]
    (fn [_ _ arg-types]
      [:tr
       (for [[i t] (map-indexed vector arg-types)]
         ^{:key i}
         [:td
          [typed-input
           {:domain-id domain-id
            :arg-type  t
            :state     (get cells i)
            :placeholder t}]])
       [:td.delete
        [:button.primary.add-fact
         {:title "Add fact (Enter)"
          :on-click (fn []
                      (try
                        (state/assert-from-form!
                          domain-id pred-name arg-types
                          (mapv #(:val @%) cells))
                        (doseq [c cells] (reset! c {:val ""}))
                        (reset! err nil)
                        (catch :default ex
                          (reset! err (.-message ex)))))}
         "+ Add"]
        (when @err [:div.err.mini @err])]])))

(defn- move-to-pill
  "Header pill showing the item's current domain, click → popover that
  lists other domains for moving. `mover` is a fn [src-id dst-id]."
  [src-id mover]
  (let [label (get-in @app-state [:domains src-id :label])]
    [:span.pill.movable
     {:title "Move to another domain"
      :on-click (fn [e] (open-popover-from-event!
                          {:kind :move-to :src src-id :mover mover} e))}
     "in " [:b label] " ▾"]))

(defn- rules-using-attr
  "Return rules whose body references `attr` — either as a Datalog pattern
  `[?e attr ?v]` or as a rule-call `(attr-name ?a ?b)`."
  [domain attr]
  (let [attr-sym (symbol (clojure.core/name attr))]
    (->> (:rules domain)
         (filter
           (fn [rule]
             (let [body (rest rule)]
               (some (fn [c]
                       (cond
                         ;; pattern [?e attr ?v]
                         (and (vector? c) (>= (count c) 3)
                              (= attr (second c))) true
                         ;; rule call (attr-name ?a ?b)
                         (and (seq? c) (symbol? (first c))
                              (= attr-sym (first c))) true
                         :else false))
                     body)))))))

(defn- rules-defining-attr
  "Return rules whose head produces facts under `attr`. For an arity-2
  rule head `(parent ?a ?b)` we materialise into `[?a :parent ?b]`; for
  arity-1 `(loved ?b)` we materialise into `[?b :loved true]` — both end
  up as the same `:attr` in the store."
  [domain attr]
  (let [attr-sym (symbol (clojure.core/name attr))]
    (->> (:rules domain)
         (filter
           (fn [rule]
             (let [head (first rule)]
               (and (seq? head) (symbol? (first head))
                    (= attr-sym (first head)))))))))

(defn- sort-indicator [dir]
  (case dir :asc " ▲" :desc " ▼" ""))

(defn- cycle-sort
  "Cycle through nil → :asc → :desc → nil for a given column."
  [ui-state col]
  (swap! ui-state update :sort
         (fn [{:keys [col-cur dir]}]
           (cond
             (not= col col-cur) {:col-cur col :dir :asc}
             (= dir :asc)       {:col-cur col :dir :desc}
             (= dir :desc)      {:col-cur nil :dir nil}
             :else              {:col-cur col :dir :asc}))))

(defn predicate-view [name arity]
  (let [ui-state (r/atom {:query "" :provs #{}
                          :sort {:col-cur nil :dir nil}})]
    (fn [name arity]
      (let [domain-id (state/current-id)
            d (state/current)
            attr (keyword name)
            all-triples (filter #(= attr (second %)) (state/all-triples domain-id))
            declared (first (filter #(and (= name (:name %))
                                          (= arity (count (:argTypes %))))
                                    (get-in d [:schema :predicates])))
            arg-types (when declared (:argTypes declared))
            defining-rules (rules-defining-attr d attr)
            using-rules (rules-using-attr d attr)
            ;; enrich with provenance
            rows (mapv (fn [[e a v :as tr]]
                         {:e e :v v :tr (vec tr)
                          :prov (state/triple-provenance domain-id tr)})
                       all-triples)
            provs-present (set (map :prov rows))
            {:keys [query provs sort]} @ui-state
            ;; filter by search
            filtered (filterv #(row-matches? query [(:e %) (:v %)]) rows)
            ;; filter by provenance (empty = show all)
            filtered (if (seq provs)
                       (filterv #(contains? provs (:prov %)) filtered)
                       filtered)
            ;; sort
            filtered (let [{:keys [col-cur dir]} sort]
                       (if (and col-cur dir)
                         (let [k (case col-cur 0 :e 1 :v 2 :prov nil)]
                           (if k
                             (vec ((if (= dir :desc) reverse identity)
                                   (sort-by (comp sort-key k) filtered)))
                             filtered))
                         filtered))
            sort-cur (:col-cur sort)
            sort-dir (:dir sort)]
        [:div.view
         [:div.view-head
          [:h2.mono (str name "/" arity)]
          [:span.desc (count all-triples) " fact"
           (when (not= 1 (count all-triples)) "s")]
          (if declared
            [:span.pill.declared
             {:title "This predicate's arg types are declared in the domain's schema."}
             "declared"]
            [:span.pill
             {:title (str "This attribute exists in stored facts but has no declared "
                          "arg types. Click 'Declare types' below to add a schema entry.")}
             "discovered"])
          (let [si (state/attr-schema-info domain-id attr)]
            (when si
              (let [vt (:db/valueType si)
                    card (:db/cardinality si)
                    vt-label (state/db-type-label vt)
                    many? (= :db.cardinality/many card)]
                [:span.pill.schema-badge
                 {:title (str "Datahike value type: " vt-label
                              " (" (clojure.core/name vt) "). Cardinality: "
                              (if many?
                                "many — a single entity may hold many values for this attribute."
                                "one — only one value per entity (overwrites on re-assert)."))}
                 (str vt-label " · " (if many? "many" "one"))])))
          [move-to-pill domain-id
           (fn [src dst] (state/move-predicate! src dst name arity))]
          [:div.actions-right
           (when declared
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm
                                  (str "Delete predicate declaration " name "/" arity
                                       "?\nFacts are not deleted."))
                             (state/delete-predicate! domain-id name arity)
                             (state/select! {:kind :rules})))}
              "Delete declaration"])]]
         [table-toolbar
          {:state-atom ui-state
           :provs-present provs-present
           :total (count rows)
           :shown (count filtered)}]
         [:table.facts
          [:thead
           [:tr
            (if declared
              (for [[i t] (map-indexed vector arg-types)]
                ^{:key i}
                [:th.sortable {:on-click #(cycle-sort ui-state i)}
                 [:div.h
                  [:span.type-icon {:title t} (type-icon-for t)]
                  [:span t]
                  [:span.sort-arrow (when (= i sort-cur) (sort-indicator sort-dir))]]])
              [:<> [:th.sortable {:on-click #(cycle-sort ui-state 0)}
                    [:div.h [:span.type-icon "◇"] [:span "subject"]
                     [:span.sort-arrow (when (= 0 sort-cur) (sort-indicator sort-dir))]]]
                   [:th.sortable {:on-click #(cycle-sort ui-state 1)}
                    [:div.h [:span.type-icon "◇"] [:span "object"]
                     [:span.sort-arrow (when (= 1 sort-cur) (sort-indicator sort-dir))]]]])
            [:th ""]]]
          [:tbody
           (if (empty? filtered)
             [:tr [:td {:col-span (inc (or (count arg-types) 2))
                        :style {:padding "24px" :text-align "center"
                                :color "var(--muted)"
                                :font-family "var(--sans-font)"}}
                   (if (empty? all-triples)
                     "No facts yet — use the row below to add one."
                     "No rows match the current filter.")]]
             (for [{:keys [tr]} filtered]
               ^{:key (pr-str tr)}
               [pred-edit-row domain-id (or arg-types []) tr]))]
          (let [effective-types (or arg-types ["atom" "atom"])]
            (when (= 2 (count effective-types))
              [:tfoot
               [pred-add-row domain-id name effective-types]]))]
     (when-not declared
       [:div.declare-hint
        "This predicate isn't declared. "
        [:a.atom-link
         {:on-click #(state/open-modal! {:kind :new-predicate
                                          :preset-name name
                                          :domain domain-id})}
         "Declare types"] " to get a typed table with dropdowns."])
     (when (and declared (not= 2 (count arg-types)))
       [:div.declare-hint
        "Add-row form only supports arity-2 predicates today. "
        [:span {:style {:color "var(--dim)"}}
         "(Arity " (count arg-types) " requires entity reification.)"]])
     (when (seq defining-rules)
       [:div.used-in
        [:h3.section-h "Defined by"]
        [:div.section-hint
         "These rules produce facts under "
         [:code (str ":" name)] " when the engine materialises derivations."]
        (for [[i r] (map-indexed vector defining-rules)
              :let [head (first r)
                    head-name (clojure.core/name (first head))
                    head-arity (count (rest head))]]
          ^{:key i}
          [:div.used-in-card
           [:div.used-in-head
            [:a.atom-link
             {:on-click #(state/select! {:kind :rule :name head-name})}
             head-name "/" head-arity]]
           [:div.rule-card.compact
            [rule-clause r]]])])
     (when (seq using-rules)
       [:div.used-in
        [:h3.section-h "Used in rules"]
        [:div.section-hint
         "These rules reference "
         [:code (str ":" name)] " in their body — they're consumers."]
        (for [[i r] (map-indexed vector using-rules)
              :let [head (first r)
                    head-name (when (and (seq? head) (symbol? (first head)))
                                (clojure.core/name (first head)))
                    head-arity (when (seq? head) (count (rest head)))]]
          ^{:key i}
          [:div.used-in-card
           (when head-name
             [:div.used-in-head
              [:a.atom-link
               {:on-click #(state/select! {:kind :rule :name head-name})}
               head-name "/" head-arity]])
           [:div.rule-card.compact
            [rule-clause r]]])])]))))

;; ---------------------------------------------------------------------------
;; Type view

(defn type-view [name]
  ;; Local chip-editor state holds the current full constructor list. We
  ;; sync it from the schema each render (via :loaded-name tracking) so
  ;; that switching types refreshes correctly.
  (let [ctors-atom (r/atom [])
        loaded-name (r/atom nil)
        last-len (r/atom 0)]
    (fn [name]
      (let [domain-id (state/current-id)
            d (state/current)
            t (first (filter #(= name (:name %)) (get-in d [:schema :types])))
            schema-ctors (vec (:constructors t))]
        ;; reset on type-switch or when the schema changed underneath us
        (when (or (not= name @loaded-name)
                  (not= (count schema-ctors) @last-len))
          (reset! ctors-atom schema-ctors)
          (reset! loaded-name name)
          (reset! last-len (count schema-ctors)))
        (if-not t
          [:div.view [:div.empty [:h3 "Type not found"]]]
          [:div.view
           [:div.view-head
            [:h2.mono name]
            [:span.desc (count schema-ctors) " value"
             (when (not= 1 (count schema-ctors)) "s")]
            [:span.pill.declared "type"]
            [:span.pill.schema-badge "ref · many"]
            [move-to-pill domain-id
             (fn [src dst] (state/move-type! src dst name))]
            [:div.actions-right
             [:button.primary.small
              {:disabled (= @ctors-atom schema-ctors)
               :on-click #(do (state/declare-type! domain-id name @ctors-atom)
                              (reset! last-len (count @ctors-atom)))}
              "Save changes"]
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm (str "Delete type " name "?"))
                             (state/delete-type! domain-id name)
                             (state/select! {:kind :rules})))}
              "Delete type"]]]
           [:div.type-editor
            [chip-editor
             {:state-atom ctors-atom
              :placeholder "type a name, Enter to add"
              :suggestions-fn #(state/untyped-atoms domain-id)}]
            [:div.hint
             "Click a chip's × to remove; suggestions are atoms in this "
             "domain that aren't typed yet. Hit "
             [:b "Save changes"] " to commit."]]
           ;; navigable list of current constructors
           (when (seq schema-ctors)
             [:div.constructor-list
              [:h3.section-h "Constructors"]
              (for [c schema-ctors]
                ^{:key c}
                [:div.nav-item {:style {:padding-left 0}}
                 [:span.icon "◇"]
                 [:span.name [:a.atom-link
                              {:on-click #(state/select! {:kind :entity
                                                          :name (keyword c)})}
                              c]]])])])))))

;; ---------------------------------------------------------------------------
;; Rule view

;; --- Rule pretty-printing (Datahike rule shape) ----------------------------

(defn- fmt-rule-arg [v]
  (cond
    (symbol? v)  (str v)
    (keyword? v) (fmt-val v)
    (string? v)  (str "\"" v "\"")
    (vector? v)  (pr-str v)
    :else        (str v)))

(defn- fmt-head [head]
  ;; head: a list like (ancestor ?a ?b)
  (let [hname (first head)
        args (rest head)]
    (str hname "(" (str/join ", " (map fmt-rule-arg args)) ")")))

(defn- fmt-where-clause [c]
  (cond
    ;; pattern: [?e :attr ?v] or [?e :attr value]
    (vector? c)
    (let [[e a v & more] c]
      (if (and (keyword? a) (nil? more))
        (str (fmt-rule-arg e) " " (fmt-val a) " " (fmt-rule-arg v))
        (pr-str c)))
    ;; rule invocation: (ancestor ?a ?b)
    (seq? c)
    (str (first c) "(" (str/join ", " (map fmt-rule-arg (rest c))) ")")
    :else (pr-str c)))

(defn- rule-clause [r]
  ;; r looks like [(head ?a ?b) <body-clause>+ ]
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

(defn rule-view [name]
  (let [d (state/current)
        entry (first (filter #(= name (:name %)) (domain-rules d)))
        clauses (or (:clauses entry) [])]
    [:div.view
     [:div.view-head
      [:h2.mono (str name "/" (:arity entry))]
      [:span.desc (count clauses) " clause"
       (when (not= 1 (count clauses)) "s")]
      [:span.pill.derived "derived"]
      [:div.actions-right
       [:button
        {:on-click #(state/select! {:kind :rules})}
        "Edit in Rules"]]]
     (for [[i r] (map-indexed vector clauses)]
       ^{:key i}
       [:div.rule-card
        [rule-clause r]])
     [:div.hint
      "Rules live in the domain's Rules editor. Edit there to change."]]))

;; ---------------------------------------------------------------------------
;; Query view (saved query)

(defn query-view [name]
  (let [d (state/current)
        q (first (filter #(= name (:name %)) (get-in d [:schema :queries])))
        local (r/atom {:text (:text q) :loaded-name name :result nil})]
    (fn [name]
      (let [domain-id (state/current-id)
            d (state/current)
            q (first (filter #(= name (:name %)) (get-in d [:schema :queries])))]
        ;; If the user switched to a different saved query, reset local
        ;; so the textarea shows the new query's body, not the previous one.
        (when (not= name (:loaded-name @local))
          (reset! local {:text (:text q) :loaded-name name :result nil}))
        (if-not q
          [:div.view [:div.empty [:h3 "Query not found"]]]
          [:div.view
           [:div.view-head
            [:h2.mono (:name q)]
            [:span.pill "saved query"]
            [move-to-pill domain-id
             (fn [src dst] (state/move-query! src dst (:name q)))]
            [:div.actions-right
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm (str "Delete saved query " (:name q) "?"))
                             (state/delete-query! domain-id (:name q))
                             (state/select! {:kind :rules})))} "Delete"]]]
           [:div.program-editor
            [:textarea {:value (:text @local)
                        :on-change #(swap! local assoc :text (.. % -target -value))
                        :spellCheck "false"
                        :rows 3}]
            [:div.footer
             [:button.primary
              {:on-click (fn []
                           (let [r (state/run-query domain-id (:text @local))]
                             (swap! local assoc :result r)))}
              "Run"]
             [:button
              {:on-click (fn []
                           (state/save-query! domain-id (:name q) (:text @local)))}
              "Save"]]]
           (when-let [r (:result @local)]
             [:div {:style {:margin-top "16px"}}
              (cond
                (:error r) [:div.results.err (:error r)]
                (empty? (:rows r)) [:div.results [:div.summary "no solutions"]]
                :else
                (let [rows  (:rows r)
                      total (count rows)
                      shown (take result-row-cap rows)]
                  [:div.results
                   [:div.summary total " solution" (when (not= 1 total) "s")
                    (when (> total result-row-cap)
                      [:span.trunc " · showing first " result-row-cap])]
                   (for [[i row] (map-indexed vector shown)]
                     ^{:key i}
                     [:div.row
                      (for [[k v] (map vector (:vars r) row)]
                        ^{:key k}
                        [:span.b [:span.k k] " = " [:span.v (atom-link v)]])])]))])])))))

;; ---------------------------------------------------------------------------
;; Entity view

(defn- entity-add-form
  "Inline add-fact form for an entity, with the entity fixed in one slot
  and a typed input in the other. Commits on Enter or '+ Add'."
  [domain-id entity attr role arg-types]
  (let [val (r/atom {:val ""})
        err (r/atom nil)]
    (fn [_ entity attr role arg-types]
      (let [other-type (case role
                         :subject (or (second arg-types) "atom")
                         :object  (or (first arg-types)  "atom"))
            commit (fn []
                     (try
                       (let [v (state/coerce-value domain-id other-type (:val @val))
                             triple (case role
                                      :subject [entity attr v]
                                      :object  [v attr entity])]
                         (state/assert-triple! domain-id triple "form")
                         (reset! val {:val ""})
                         (reset! err nil))
                       (catch :default ex
                         (reset! err (.-message ex)))))
            input [typed-input {:domain-id domain-id :arg-type other-type
                                :state val :placeholder other-type
                                :on-enter commit}]
            fixed [:span.entity-fixed (fmt-val entity)]]
        [:div.entity-add
         [:div.row
          (if (= role :subject) fixed input)
          [:span.arrow "→"]
          (if (= role :subject) input fixed)
          [:button.primary.small {:on-click commit :title "Add (Enter)"} "+"]]
         (when @err [:div.err.mini @err])]))))

(defn- entity-relation-roles
  "Returns the set of roles `:subject` / `:object` the entity plays for these
  triples (an attr group)."
  [entity triples]
  (cond-> #{}
    (some (fn [[e _ _]] (= e entity)) triples) (conj :subject)
    (some (fn [[_ _ v]] (= v entity)) triples) (conj :object)))

(defn- canonical-symmetric
  "Drop self-loops and pick one direction for symmetric pairs.
  When both [E A V] and [V A E] exist, keep only the one with E ≤ V lex
  order. Used to de-noise the entity view where derived symmetric
  relations (fellow-resident, coworker, sibling…) otherwise appear twice."
  [triples]
  (let [present (set (map (juxt first second #(nth % 2)) triples))]
    (filter
      (fn [[e a v]]
        (cond
          (= e v) false
          (and (contains? present [v a e])
               (pos? (compare (str e) (str v))))
          false
          :else true))
      triples)))

(defn- relation-form-block
  [domain-id entity attr arg-types roles]
  [:div.add-zone
   (for [role (sort roles)]
     ^{:key role}
     [entity-add-form domain-id entity attr role arg-types])])

(defn- entity-note-block
  "Markdown note section: view-mode renders, edit-mode shows a textarea.
  Empty + view-mode shows a tiny + Add note button."
  [domain-id entity]
  (let [editing? (r/atom false)
        draft (r/atom nil)]
    (fn [domain-id entity]
      (let [note (state/entity-note domain-id entity)]
        (if @editing?
          [:div.entity-note.editing
           [:textarea.note-edit
            {:auto-focus true
             :placeholder "Markdown notes about this entity. [[wikilinks]] are linked atoms."
             :default-value (or @draft note "")
             :on-change #(reset! draft (.. % -target -value))}]
           [:div.note-controls
            [:button.primary.small
             {:on-click (fn []
                          (state/set-note! domain-id entity (or @draft note ""))
                          (reset! editing? false)
                          (reset! draft nil))}
             "Save"]
            [:button.ghost.small
             {:on-click (fn [] (reset! editing? false) (reset! draft nil))}
             "Cancel"]]]
          (if (and note (not (str/blank? note)))
            [:div.entity-note
             [markdown note]
             [:div.note-controls
              [:button.ghost.small {:on-click #(reset! editing? true)} "Edit note"]]]
            [:button.ghost.small.add-note
             {:on-click #(reset! editing? true)}
             "+ Add note"]))))))

(defn entity-view [e]
  (let [ui-state (r/atom {:open-add nil :dedupe? true})]
    (fn [e]
      (let [domain-id (state/current-id)
            d (state/current)
            triples (state/entity-mentions domain-id e)
            ;; hide :note from the relation groups — it has its own section.
            triples (remove #(= :note (second %)) triples)
            by-attr (group-by second triples)
            declared-preds (->> (get-in d [:schema :predicates])
                                (filter #(= 2 (count (:argTypes %)))))
            seen-attrs (set (keys by-attr))
            unseen-preds (->> declared-preds
                              (remove #(seen-attrs (keyword (:name %)))))
            dedupe? (:dedupe? @ui-state)]
        [:div.view
         [:div.entity-hero
          [:div.avatar (str/upper-case (subs (fmt-val e) 0 1))]
          [:div
           [:div.mono.title (fmt-val e)]
           [:div.subtitle (count triples) " mention"
            (when (not= 1 (count triples)) "s")]]
          [:div.entity-controls
           [:button.ghost.small
            {:title (if dedupe?
                      "Currently hiding self-loops and one direction of symmetric pairs"
                      "Showing every triple, including duplicates and self-loops")
             :on-click #(swap! ui-state update :dedupe? not)}
            (if dedupe? "showing canonical" "showing all")]]]

         [entity-note-block domain-id e]

         (if (empty? triples)
           [:div.empty
            [:div "No facts mention this entity yet."]
            [:div {:style {:margin-top "8px" :color "var(--dim)"}}
             "Use the picker below to add your first relation."]]
           (for [[attr trs] (sort-by (comp str first) by-attr)]
             ^{:key (str attr)}
             (let [decl (first (filter #(and (= (name attr) (:name %))
                                             (= 2 (count (:argTypes %))))
                                       (get-in d [:schema :predicates])))
                   arg-types (when decl (:argTypes decl))
                   roles (entity-relation-roles e trs)
                   shown (if dedupe? (canonical-symmetric trs) trs)
                   hidden (- (count trs) (count shown))]
               [:div.relation-group
                [:h3.rel-attr
                 [:span.rel-name [pred-link attr]]
                 [:span.dim " — " (count shown)
                  (when (pos? hidden)
                    [:span.tiny-hint
                     " (" hidden " " (if (= 1 hidden) "duplicate" "duplicates")
                     " filtered)"])]
                 (when decl [:span.pill.declared.mini "declared"])]
                (for [[i [ee _ vv :as tr]] (map-indexed vector shown)]
                  ^{:key (str (pr-str tr))}
                  [:div.fact-row
                   [:span (atom-link ee)]
                   [:span.arrow "→"]
                   [:span (atom-link vv)]
                   [:button.ghost.danger.remove
                    {:on-click #(state/retract-triple! domain-id (vec tr))
                     :title "Retract"} "×"]])
                [relation-form-block domain-id e attr arg-types roles]])))

         ;; Linked from notes — wikilink backlinks
         (let [bl (state/note-backlinks domain-id e)]
           (when (seq bl)
             [:div.backlinks
              [:h3.section-h "Linked from notes"]
              (for [[from-ent text] bl]
                ^{:key from-ent}
                [:div.backlink-row
                 [:div.bl-head
                  (atom-link from-ent)]
                 [:div.bl-snippet
                  (let [s (str/replace text #"\s+" " ")]
                    (if (> (count s) 140) (str (subs s 0 140) "…") s))]])]))

         ;; Add another relation
         (when (seq declared-preds)
           (let [open-add (:open-add @ui-state)]
             [:div.new-relation
              [:h3.section-h "Add another relation"]
              [:div.pred-picker
               (for [p unseen-preds]
                 ^{:key (:name p)}
                 [:button.chip
                  {:class (when (and open-add (= (:name p) (:name open-add))) "active")
                   :on-click #(swap! ui-state assoc :open-add
                                     {:name (:name p) :arg-types (:argTypes p)
                                      :role :subject})}
                  (:name p) "/" (count (:argTypes p))])
               (when (empty? unseen-preds)
                 [:span.dim {:style {:padding "4px 0"}}
                  "All declared predicates already have facts for this entity."])]
              (when-let [{:keys [name arg-types role]} open-add]
                [:div.add-inline
                 [:div.role-toggle
                  [:button {:class (when (= role :subject) "active")
                            :on-click #(swap! ui-state assoc-in [:open-add :role] :subject)}
                   "as subject"]
                  [:button {:class (when (= role :object) "active")
                            :on-click #(swap! ui-state assoc-in [:open-add :role] :object)}
                   "as object"]
                  [:button.ghost {:on-click #(swap! ui-state assoc :open-add nil)} "Cancel"]]
                 [entity-add-form domain-id e (keyword name) role arg-types]])]))]))))

;; ---------------------------------------------------------------------------
;; Modal

(defn- text-field [{:keys [label id placeholder default rows]}]
  (let [el-id (str "f-" (name id))]
    [:div.field
     [:label label]
     (if rows
       [:textarea {:id el-id :rows rows :default-value (or default "")
                   :placeholder placeholder :spellCheck "false"}]
       [:input {:id el-id :default-value (or default "") :placeholder placeholder}])]))

(defn- read-field [id]
  (some-> (.getElementById js/document (str "f-" (name id))) .-value str/trim))

(defn- arg-type-options
  "List of arg-type strings available in the given domain: the built-ins
  plus every declared (enum) type."
  [domain-id]
  (let [declared (->> (get-in @app-state [:domains domain-id :schema :types])
                      (map :name)
                      sort)]
    (vec (concat ["atom" "int" "string"] declared))))

(defn new-predicate-form
  "Form-2 component for declaring a new predicate. Dynamic list of per-arg
  dropdowns populated from the built-in arg types plus the domain's
  declared types."
  [{:keys [domain preset-name]}]
  (let [pname (r/atom (or preset-name ""))
        args  (r/atom ["atom" "atom"])]
    (fn [{:keys [domain]}]
      (let [opts (arg-type-options domain)
            valid? (and (seq (str/trim @pname)) (seq @args))]
        [:<>
         [:h3 "New predicate"]
         [:div.modal-sub "A typed relation. Each arg picks its type from "
          "the built-ins (" [:code "atom"] ", " [:code "int"] ", "
          [:code "string"] ") or any declared type in this domain."]
         [:div.field
          [:label "Name"]
          [:input {:placeholder "e.g. age"
                   :value @pname
                   :auto-focus true
                   :on-change #(reset! pname (.. % -target -value))}]]
         [:div.field
          [:label "Arg types"]
          [:div.arg-rows
           (for [[i t] (map-indexed vector @args)]
             ^{:key i}
             [:div.arg-row
              [:span.arg-idx (str "arg " (inc i))]
              [:select.typed
               {:value t
                :on-change (fn [e]
                             (let [v (.. e -target -value)]
                               (swap! args assoc i v)))}
               (for [o opts] ^{:key o} [:option {:value o} o])]
              (when (> (count @args) 1)
                [:button.ghost.small
                 {:title "Remove arg"
                  :on-click #(swap! args (fn [xs]
                                           (vec (concat (subvec xs 0 i)
                                                        (subvec xs (inc i))))))}
                 "×"])])
           [:button.ghost.small
            {:on-click #(swap! args conj "atom")}
            "+ add arg"]]]
         [:div.modal-actions
          [:button {:on-click state/close-modal!} "Cancel"]
          [:button.primary
           {:disabled (not valid?)
            :on-click (fn []
                        (let [n (str/trim @pname)]
                          (when valid?
                            (state/declare-predicate! domain n @args)
                            (state/select! {:kind :predicate :name n
                                            :arity (count @args)}))
                          (state/close-modal!)))}
           "Declare"]]]))))

(defn new-type-form
  "Form-2 component for creating a new type. Name field + chip-editor for
  constructors, with auto-suggest of existing untyped atoms in the domain."
  [domain-id]
  (let [type-name (r/atom "")
        ctors (r/atom [])]
    (fn [domain-id]
      [:<>
       [:h3 "New type"]
       [:div.modal-sub "A named enum of keyword values, e.g. "
        [:code "person"] " = "
        [:code "alice | bob | carol"]
        ". When used as a predicate arg type, the predicate stores "
        [:code ":db.type/ref"] " references to these constructors."]
       [:div.field
        [:label "Name"]
        [:input {:placeholder "e.g. person"
                 :value @type-name
                 :auto-focus true
                 :on-change #(reset! type-name (.. % -target -value))}]]
       [:div.field
        [:label "Constructors"]
        [chip-editor
         {:state-atom ctors
          :placeholder "type a name, Enter to add"
          :suggestions-fn #(state/untyped-atoms domain-id)}]]
       [:div.modal-actions
        [:button {:on-click state/close-modal!} "Cancel"]
        [:button.primary
         {:disabled (or (str/blank? @type-name) (empty? @ctors))
          :on-click (fn []
                      (let [n (str/trim @type-name)]
                        (when (seq n)
                          (state/declare-type! domain-id n @ctors)
                          (state/select! {:kind :type :name n}))
                        (state/close-modal!)))}
         "Create type"]]])))

(defn modal []
  (let [m (:modal @app-state)]
    (when m
      [:div.modal-overlay
       {:on-click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (state/close-modal!)))}
       [:div.modal-card
        (case (:kind m)
          :new-domain
          [:<>
           [:h3 "New domain"]
           [:div.modal-sub "A self-contained Naga program with its own facts, rules, and schema."]
           [text-field {:label "Name" :id "domain-label" :placeholder "e.g. family"}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (let [lbl (read-field "domain-label")]
                            (when (seq lbl) (state/create-domain! lbl))
                            (state/close-modal!)))} "Create"]]]

          :new-type
          [new-type-form (:domain m)]

          :new-predicate
          [new-predicate-form {:domain (:domain m)
                               :preset-name (:preset-name m)}]

          :new-rule
          [:<>
           [:h3 "New rule"]
           [:div.modal-sub "Datalog rule. Format: "
            [:code "[(head ?a ?b) body…]"]]
           [text-field {:label "Rule (EDN)" :id "rule-text"
                        :placeholder "[(ancestor ?a ?d) [?a :parent ?d]]"
                        :rows 4}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (try
                            (let [rule-text (read-field "rule-text")
                                  parsed (when (seq rule-text)
                                           (reader/read-string rule-text))
                                  d (get-in @app-state [:domains (:domain m)])
                                  rules (vec (:rules d))
                                  new-rules (conj rules parsed)]
                              (when parsed
                                (state/set-rules! (:domain m) new-rules)
                                (state/select! {:kind :rules}))
                              (state/close-modal!))
                            (catch :default ex
                              (js/alert (str "Bad rule: " (.-message ex))))))}
             "Append rule"]]]

          :new-query
          [:<>
           [:h3 "New saved query"]
           [text-field {:label "Name" :id "q-name" :placeholder "e.g. all-adults"}]
           [text-field {:label "Query body" :id "q-text"
                        :placeholder "e.g. parent(?p, ?c)"}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (let [name (read-field "q-name")
                                text (read-field "q-text")]
                            (when (seq name)
                              (state/save-query! (:domain m) name text)
                              (state/select! {:kind :query :name name}))
                            (state/close-modal!)))}
             "Save"]]]

          :rename-domain
          [:<>
           [:h3 "Rename domain"]
           [:div.modal-sub "Change the display name of this domain."]
           [text-field {:label "Name" :id "rename-domain-label"
                        :default (get-in @app-state [:domains (:domain m) :label])}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (let [lbl (read-field "rename-domain-label")]
                            (when (seq lbl)
                              (state/rename-domain! (:domain m) lbl))
                            (state/close-modal!)))} "Rename"]]]

          :settings
          [:<>
           [:h3 "Settings"]
           [:div.modal-sub "Local data lives in this browser's storage."]
           [:div.settings-section
            [:h4 "Data"]
            [:div.settings-row
             [:div.lbl "Snapshot"
              [:div.hint "EDN export of every domain. Import replaces current state."]]
             [:div
              [:button
               {:on-click (fn []
                            (let [snap (state/serializable @app-state)
                                  text (storage/snapshot->json snap)
                                  fn (str "golova-"
                                          (.toISOString (js/Date.))
                                          ".edn")]
                              (storage/download-blob! fn text)))}
               "Export"]
              [:button
               {:on-click (fn []
                            (let [inp (.createElement js/document "input")]
                              (set! (.-type inp) "file")
                              (set! (.-accept inp) ".edn,.txt,application/edn,text/plain")
                              (set! (.-onchange inp)
                                    (fn [e]
                                      (when-let [f (-> e .-target .-files (aget 0))]
                                        (let [rdr (js/FileReader.)]
                                          (set! (.-onload rdr)
                                                (fn [ev]
                                                  (try
                                                    (let [txt (.. ev -target -result)
                                                          snap (storage/parse-snapshot txt)]
                                                      (state/import-snapshot! snap)
                                                      (state/close-modal!))
                                                    (catch :default ex
                                                      (js/alert (str "Bad snapshot: " (.-message ex)))))))
                                          (.readAsText rdr f)))))
                              (.click inp)))}
               "Import…"]]]
            [:div.settings-row.danger
             [:div.lbl "Reset all data"
              [:div.hint "Wipes every domain and replaces with the starter program. This can't be undone."]]
             [:div
              [:button.ghost.danger
               {:on-click (fn []
                            (when (js/confirm "Wipe everything and reset to the starter? This can't be undone.")
                              (state/reset-all!)
                              (state/close-modal!)))}
               "Reset"]]]]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Close"]]]

          nil)]])))

;; ---------------------------------------------------------------------------
;; Command palette (Cmd-K).

(defn- domain-entities
  "Set of keyword entities in a domain's db + type constructors."
  [d]
  (let [triples (state/all-triples (:id d))
        in-store (->> triples
                      (mapcat (fn [[e _ v]] [e v]))
                      (filter keyword?)
                      set)
        ctors (->> (get-in d [:schema :types])
                   (mapcat (fn [t] (map keyword (:constructors t))))
                   set)]
    (into (or in-store #{}) ctors)))

(defn- palette-candidates
  "Build the full candidate list for the current state. Each item:
   {:kind … :label … :sublabel … :icon … :run (fn []) :sort-key …}"
  [state]
  (let [doms   (:domains state)
        cur-id (:current-domain state)
        d      (get doms cur-id)
        nav    (fn [sel] #(do (state/close-palette!) (state/select! sel)))
        modal  (fn [m]   #(do (state/close-palette!) (state/open-modal! m)))]
    (concat
     ;; Commands — always available
     [{:kind :cmd :label "New domain…" :icon "+"
       :run (modal {:kind :new-domain})}
      {:kind :cmd :label "Open settings" :icon "⚙"
       :run (modal {:kind :settings})}]
     (when cur-id
       [{:kind :cmd :label (str "New type in " (:label d) "…") :icon "◆"
         :run (modal {:kind :new-type :domain cur-id})}
        {:kind :cmd :label (str "New predicate in " (:label d) "…") :icon "▦"
         :run (modal {:kind :new-predicate :domain cur-id})}
        {:kind :cmd :label (str "New rule in " (:label d) "…") :icon "ƒ"
         :run (modal {:kind :new-rule :domain cur-id})}
        {:kind :cmd :label (str "Save query in " (:label d) "…") :icon "?"
         :run (modal {:kind :new-query :domain cur-id})}
        {:kind :cmd :label (str "Open rules for " (:label d)) :icon "ƒ"
         :run (nav {:kind :rules})}])
     ;; Domains
     (for [[id dd] (sort-by (comp str first) doms)]
       {:kind :domain :label (:label dd) :sublabel "domain" :icon "□"
        :run #(do (state/close-palette!) (state/switch-domain! id))})
     ;; Things in the current domain
     (when cur-id
       (concat
        (for [t (get-in d [:schema :types])]
          {:kind :type :label (:name t) :sublabel "type" :icon "◆"
           :run (nav {:kind :type :name (:name t)})})
        (for [p (domain-predicates d)]
          {:kind :pred
           :label (str (:name p) "/" (:arity p))
           :sublabel (if (:declared? p) "predicate" "predicate (discovered)")
           :icon (if (:declared? p) "▦" "▢")
           :run (nav {:kind :predicate :name (:name p) :arity (:arity p)})})
        (for [r (domain-rules d)]
          {:kind :rule
           :label (str (:name r) "/" (:arity r))
           :sublabel "rule"
           :icon "ƒ"
           :run (nav {:kind :rule :name (:name r)})})
        (for [q (get-in d [:schema :queries])]
          {:kind :query :label (:name q) :sublabel "saved query" :icon "?"
           :run (nav {:kind :query :name (:name q)})})
        (for [e (sort-by str (domain-entities d))]
          {:kind :entity :label (fmt-val e) :sublabel "entity" :icon "◇"
           :run (nav {:kind :entity :name e})}))))))

(defn- match-score
  "Return a positive score for candidates matching `q`, or nil to drop.
  Cheap fuzzy: prefer prefix matches, then substring."
  [q label]
  (let [lab (str/lower-case label)
        q   (str/lower-case (str/trim q))]
    (cond
      (str/blank? q)              0
      (str/starts-with? lab q)    (- 200 (count label))
      (str/includes? lab q)       (- 100 (count label))
      :else                       nil)))

(defn- kind-rank [k]
  ;; Order groups when query is blank
  (get {:cmd 0 :domain 1 :type 2 :pred 3 :rule 4 :query 5 :entity 6} k 9))

(defn- filter-palette [cands q]
  (->> cands
       (remove nil?)
       (keep (fn [c]
               (when-let [s (match-score q (:label c))]
                 (assoc c :score s))))
       (sort-by (juxt #(- (:score %)) #(kind-rank (:kind %)) :label))
       vec))

(defn palette []
  (let [p (:palette @app-state)]
    (when (:open? p)
      (let [cands (filter-palette (palette-candidates @app-state) (:query p))
            n (count cands)
            idx (if (pos? n) (mod (max 0 (or (:index p) 0)) n) 0)
            chosen (when (pos? n) (nth cands idx))]
        [:div.palette-overlay
         {:on-click (fn [e]
                      (when (= (.-target e) (.-currentTarget e))
                        (state/close-palette!)))}
         [:div.palette-card
          [:div.palette-search
           [:span.k "⌘K"]
           [:input.palette-input
            {:placeholder "Search or jump anywhere — type a name, predicate, rule…"
             :auto-focus true
             :value (:query p)
             :on-change #(state/set-palette-query! (.. % -target -value))
             :on-key-down (fn [e]
                            (cond
                              (= "Escape" (.-key e))
                              (state/close-palette!)
                              (= "ArrowDown" (.-key e))
                              (do (.preventDefault e) (state/palette-move! 1))
                              (= "ArrowUp" (.-key e))
                              (do (.preventDefault e) (state/palette-move! -1))
                              (= "Enter" (.-key e))
                              (when chosen
                                (.preventDefault e)
                                ((:run chosen)))))}]
           [:span.count (str n " result" (when (not= 1 n) "s"))]]
          (if (zero? n)
            [:div.palette-empty "No matches. Try a different query."]
            [:div.palette-list
             (for [[i c] (map-indexed vector cands)]
               ^{:key i}
               [:div.palette-item {:class (when (= i idx) "active")
                                    :on-click #((:run c))
                                    :on-mouse-enter #(state/palette-set-index! i)}
                [:span.icon (:icon c)]
                [:span.lbl (:label c)]
                (when (:sublabel c) [:span.sub (:sublabel c)])])])
          [:div.palette-foot
           [:span [:kbd "↑↓"] " navigate "]
           [:span [:kbd "↵"] " select "]
           [:span [:kbd "esc"] " close"]]]]))))

;; ---------------------------------------------------------------------------
;; Floating popovers (quick-add, domain menu, move-to).

(defn popover []
  (let [p (:popover @app-state)]
    (when p
      [:div.popover-overlay
       {:on-click (fn [e]
                    (when (= (.-target e) (.-currentTarget e))
                      (close-popover!)))}
       (case (:kind p)
         :domain-menu
         [popover-shell (:anchor p)
          [:div.popover-card
           [:div.popover-title
            [:b (get-in @app-state [:domains (:domain p) :label])]]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-type :domain (:domain p)}))}
            [:span.k "◆"] " Add type"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-predicate :domain (:domain p)}))}
            [:span.k "▦"] " Add predicate"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-rule :domain (:domain p)}))}
            [:span.k "ƒ"] " Add rule"]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :new-query :domain (:domain p)}))}
            [:span.k "?"] " Add saved query"]
           [:div.popover-sep]
           [:button.popover-item
            {:on-click #(do (close-popover!)
                            (state/open-modal! {:kind :rename-domain
                                                 :domain (:domain p)}))}
            [:span.k "✎"] " Rename…"]
           [:button.popover-item.danger
            {:on-click (fn []
                         (close-popover!)
                         (let [d (get-in @app-state [:domains (:domain p)])]
                           (when (js/confirm
                                  (str "Delete domain " (:label d)
                                       "?\nAll its facts, rules, and schema go with it."))
                             (state/delete-domain! (:domain p)))))}
            [:span.k "⌫"] " Delete domain"]]]

         :move-to
         (let [{:keys [src mover]} p
               others (filter #(not= src (first %)) (:domains @app-state))]
           [popover-shell (:anchor p)
            [:div.popover-card
             [:div.popover-title "Move to"]
             (if (empty? others)
               [:div.popover-hint "No other domains."]
               (for [[id d] (sort-by (comp str first) others)]
                 ^{:key id}
                 [:button.popover-item
                  {:on-click (fn []
                               (mover src id)
                               (state/switch-domain! id)
                               (close-popover!))}
                  (:label d)]))]])

         nil)])))

;; ---------------------------------------------------------------------------
;; Root layout

(defn main []
  (let [s @app-state
        sel (:selection s)]
    (case (:kind sel)
      :home      [home-view]
      :type      [type-view (:name sel)]
      :predicate [predicate-view (:name sel) (:arity sel)]
      :rule      [rule-view (:name sel)]
      :query     [query-view (:name sel)]
      :entity    [entity-view (:name sel)]
      (if (:current-domain s)
        [rules-view]
        [home-view]))))

(defn root []
  [:<>
   [sidebar]
   [:main
    [topbar]
    [main]]
   [modal]
   [popover]
   [palette]])
