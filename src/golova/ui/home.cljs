(ns golova.ui.home
  "Home view: Quick add + Recent activity (side-by-side), Pinned queries,
  Getting started (glossary, collapsible), Domains grid."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link pred-link fmt-relative]]
            [golova.ui.typed :refer [constructor-type-map type-value]]))

;; ---------------------------------------------------------------------------
;; Glossary (onboarding)

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
    [:span.def "A UI/organisational group. Domains are first-class atoms — "
     "each one's a navigable entity with members (via :in-domain) and an "
     "optional parent (via :domain-parent). Predicates and rules are global; "
     "the :domain field on each declaration controls sidebar placement only."]]
   [:div.gloss-item
    [:span.term "Notes"]
    [:span.def "Any entity can have a markdown note. Use "
     [:code "[[wikilink]]"] " syntax inside a note to link to another atom."]]])

;; ---------------------------------------------------------------------------
;; Quick add scratch

(defn- parse-scratch-input
  "Read a textarea blob as `[ … ]` of EDN forms. Returns
  {:triples [...] :err nil} or {:triples [] :err msg}."
  [text]
  (try
    (let [parsed (when-not (str/blank? text)
                   (reader/read-string (str "[" text "]")))
          triples (filterv #(and (vector? %) (= 3 (count %))) parsed)
          skipped (- (count parsed) (count triples))]
      (cond
        (empty? triples)
        {:triples [] :err "No valid triples. Each line should be like [:s :p :o]."}
        (pos? skipped)
        {:triples triples :err (str skipped " input(s) skipped (not 3-vectors).")}
        :else {:triples triples :err nil}))
    (catch :default e
      {:triples [] :err (or (.-message e) (str e))})))

(defn quick-scratch
  "Compact form for asserting one or more triples into a chosen domain.
  Each line of the textarea is an EDN triple `[:s :p :v]`. ⌘↵ or Add commits."
  []
  (let [text  (r/atom "")
        msg   (r/atom nil)
        dom   (r/atom nil)]
    (fn []
      (let [{:keys [current-domain]} @app-state
            domains (state/domains-list)
            target (or @dom current-domain (-> domains first :id))
            commit (fn []
                     (let [{:keys [triples err]} (parse-scratch-input @text)]
                       (cond
                         (and (empty? triples) err)
                         (reset! msg {:kind :err :text err})

                         (nil? target)
                         (reset! msg {:kind :err :text "No domain — create one first."})

                         :else
                         (let [attrs (set (map (comp keyword second) triples))
                               _ (doseq [tr triples]
                                   (state/assert-triple! tr "scratch" target))
                               rejs (:rejections @app-state)
                               my-rejs (filter #(contains? attrs (:attribute %))
                                               rejs)]
                           (reset! text "")
                           (if (seq my-rejs)
                             (reset! msg
                                     {:kind :err
                                      :text (str (count my-rejs) " of "
                                                 (count triples)
                                                 " rejected — "
                                                 (-> my-rejs first :message))})
                             (reset! msg
                                     {:kind :ok
                                      :text (str "added " (count triples)
                                                 " fact"
                                                 (when (not= 1 (count triples)) "s")
                                                 (when err (str " · " err)))}))))))]
        [:div.quick-scratch
         [:div.qs-head
          [:span.qs-label "Quick add to"]
          [:select.qs-domain
           {:value (or (some-> target name) "")
            :disabled (empty? domains)
            :on-change #(reset! dom (keyword (.. % -target -value)))}
           (for [{:keys [id label]} domains]
             ^{:key id} [:option {:value (name id)} label])]
          [:span.qs-hint "one triple per line: "
           [:code "[:alice :parent :bob]"]]]
         [:textarea.qs-input
          {:value @text
           :rows 3
           :placeholder "[:alice :likes :coffee]\n[:alice :age 30]"
           :spellCheck "false"
           :on-change #(do (reset! text (.. % -target -value))
                           (reset! msg nil))
           :on-key-down (fn [e]
                          (when (and (= "Enter" (.-key e))
                                     (or (.-metaKey e) (.-ctrlKey e)))
                            (.preventDefault e)
                            (commit)))}]
         [:div.qs-foot
          (when @msg
            [:span.qs-status {:class (name (:kind @msg))} (:text @msg)])
          [:span.qs-kbd [:kbd "⌘↵"]]
          [:button.primary.small
           {:disabled (str/blank? @text)
            :on-click commit}
           "Add"]]]))))

;; ---------------------------------------------------------------------------
;; Pinned queries

(defn- pinned-query-card [{:keys [domain-id name]}]
  (let [q (first (filter #(= name (:name %))
                         (get-in @app-state [:schema :queries])))
        result (when q (state/run-query (:text q)))
        d-label (some-> (state/domain-info domain-id) :label)
        cap 5]
    [:div.pinned-card
     [:div.pq-head
      [:a.pq-name {:on-click #(do (some-> domain-id state/switch-domain!)
                                  (state/select! {:kind :query :name name}))}
       name]
      (when d-label [:span.pq-dom d-label])
      (when-not (:error result)
        [:span.pq-count
         (count (:rows result)) " result"
         (when (not= 1 (count (:rows result))) "s")])]
     (cond
       (:error result)
       [:div.pq-error (str "error: " (:error result))]

       (empty? (:rows result))
       [:div.pq-empty "no solutions"]

       :else
       (let [ctor-map (constructor-type-map)
             shown (take cap (:rows result))]
         [:div.pq-rows
          (for [[i row] (map-indexed vector shown)]
            ^{:key i}
            [:div.pq-row
             (for [[k v] (map vector (:vars result) row)]
               ^{:key k}
               [:span.pq-bind
                [:span.pq-k k] " " [type-value ctor-map v]])])
          (when (> (count (:rows result)) cap)
            [:div.pq-more
             {:on-click #(do (some-> domain-id state/switch-domain!)
                             (state/select! {:kind :query :name name}))}
             "+ " (- (count (:rows result)) cap) " more"])]))]))

(defn pinned-queries-section []
  (let [pins (state/pinned-queries)]
    (if (empty? pins)
      [:div.empty-state
       "No pinned queries yet. Open a saved query and click "
       [:b "Pin to Home"] " to surface its results here."]
      [:div.pinned-list
       (doall
        (for [{:keys [domain-id name] :as p} pins]
          ^{:key (str (clojure.core/name domain-id) "/" name)}
          [pinned-query-card p]))])))

;; ---------------------------------------------------------------------------
;; Recent activity feed

(defn activity-feed []
  (let [events (state/recent-events 15)
        domains (state/domains-list)
        ctor-map (constructor-type-map)]
    [:div.activity-feed
     (cond
       (empty? domains)
       [:div.empty-state "Create a domain to start logging activity."]

       (empty? events)
       [:div.empty-state "No activity yet. Use the scratch above to add a fact."]

       :else
       (doall
        (for [{:keys [id at op triple source]} events
              :let [[e a v] triple
                    dom (when (keyword? e) (state/entity-domain e))
                    dom-label (some-> dom state/domain-info :label)]]
          ^{:key id}
          [:div.activity-row {:class (str "op-" (name op))}
           [:span.act-when {:title (.toLocaleString (js/Date. at))}
            (fmt-relative at)]
           (when dom-label
             [:span.act-dom {:on-click #(state/switch-domain! dom)}
              dom-label])
           [:span.act-op (case op :assert "+" :retract "−" (name op))]
           [:span.act-triple
            [:span.act-sub (atom-link e)]
            " " [pred-link a] " "
            [:span.act-obj [type-value ctor-map v]]]
           (when source
             [:span.act-src {:title (str "source: " source)} source])])))]))

;; ---------------------------------------------------------------------------
;; Top-level home view

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

     [:div.home-grid
      [:div.section-card
       [:div.section-card-head
        [:h3 "Quick add"]]
       [:div.section-card-body
        [quick-scratch]]]

      [:div.section-card
       [:div.section-card-head
        [:h3 "Recent activity"]]
       [:div.section-card-body
        [activity-feed]]]]

     [:div.section-card
      [:div.section-card-head
       [:h3 "Pinned queries"]]
      [:div.section-card-body
       [pinned-queries-section]]]

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
        (doall
         (for [{:keys [id label]} (state/domains-list)]
           (let [n-facts (count (state/triples-in-domain id))
                 n-preds (count (state/declared-predicates-in id))
                 n-rules (count (state/rules-in id))]
             ^{:key id}
             [:div.domain-card
              {:on-click #(state/switch-domain! id)}
              [:div.dc-name label]
              [:div.dc-meta
               [:span n-facts " facts"]
               [:span n-preds " preds"]
               [:span n-rules " rules"]]])))
        [:div.domain-card.new
         {:on-click #(state/open-modal! {:kind :new-domain})}
         [:div.dc-name "+ New domain"]
         [:div.dc-meta "Empty"]]]]]]))
