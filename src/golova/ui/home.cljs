(ns golova.ui.home
  "Home view: Quick add + Recent activity, Pinned queries, Getting started,
  Domains grid — all using antd Cards, Descriptions, List, and Statistic."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            ["@ant-design/icons" :as icons]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [atom-link pred-link fmt-relative]]
            [golova.ui.typed :refer [constructor-type-map type-value]]
            [golova.ui.antd :as antd]))

;; ---------------------------------------------------------------------------
;; Glossary (onboarding)

(defn- glossary-items []
  [{:title "Atom"
    :description [:span "A named thing — " [:code "alice"] ", " [:code "sf"] ", "
                  [:code "dune"] ". Stored as a Datahike "
                  [:code ":db/ident"] " so you can refer to it by keyword."]}
   {:title "Predicate"
    :description [:span "An attribute (relation), like " [:code ":parent"] " or "
                  [:code ":lives-in"] ". "
                  [:b "▦ declared"] " = you set up the arg types; "
                  [:b "▢ discovered"] " = inferred from data."]}
   {:title "Rule"
    :description [:span "A Datalog inference clause, like "
                  [:code "[(ancestor ?a ?d) [?a :parent ?d]]"]
                  ". Rules run to fixed-point at every rebuild."]}
   {:title "Fact provenance"
    :description [:span
                  [:> (.-Tag antd/antd) {:color "green"} "event"]
                  " — asserted via UI or import. "
                  [:> (.-Tag antd/antd) {:color "purple"} "derived"]
                  " — produced by a rule. "
                  [:> (.-Tag antd/antd) {:color "orange"} "imported"]
                  " — pulled from another domain."]}
   {:title "Domain"
    :description "A UI/organisational group. Domains are first-class atoms with members and optional parents."}
   {:title "Notes"
    :description [:span "Any entity can have a markdown note. Use "
                  [:code "[[wikilink]]"] " syntax to link atoms."]}])

(defn- glossary []
  [:div.glossary {:style {:marginBottom 16}}
   (for [{:keys [title description]} (glossary-items)]
     ^{:key title}
     [:> (.-ListItem antd/antd)
      [:> (.-ListItemMeta antd/antd)
       {:title title
        :description (r/as-element description)}]])])

;; ---------------------------------------------------------------------------
;; Quick add scratch

(defn- parse-scratch-input [text]
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

(defn quick-scratch []
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
                                      :text (str "Added " (count triples)
                                                 " fact"
                                                 (when (not= 1 (count triples)) "s")
                                                 (when err (str " · " err)))}))))))]
        [:div.quick-add-card
         [:div.quick-add-header
          [:span.quick-add-label "Add to"]
          [:> (.-Select antd/antd)
           {:value (or (some-> target name) "")
                :disabled (empty? domains)
                :size "small"
                :style #js {:width 140}
                :onChange #(reset! dom (keyword %))
                :options (clj->js
                          (for [{:keys [id label]} domains]
                            {:value (name id) :label label})
                          :keyword-fn name)}]
          [:span.quick-add-hint
           "one triple per line: "
           [:code "[:alice :parent :bob]"]]]
         [:> (.-InputTextArea antd/antd)
          {:value @text
               :rows 3
               :placeholder "[:alice :likes :coffee]\n[:alice :age 30]"
               :spellCheck "false"
               :onChange #(do (reset! text (.. % -target -value))
                              (reset! msg nil))
               :onKeyDown (fn [e]
                            (when (and (= "Enter" (.-key e))
                                       (or (.-metaKey e) (.-ctrlKey e)))
                              (.preventDefault e)
                              (commit)))}]
         [:div.quick-add-footer
          (when @msg
            [:> (.-Alert antd/antd)
             {:type (if (= :err (:kind @msg)) "error" "success")
                  :message (:text @msg)
                  :banner true
                  :showIcon false
                  :style #js {:flex 1}}])
          [:span.quick-add-kbd [:kbd "⌘↵"]]
          [:> (.-Button antd/antd)
           {:type "primary"
                :size "small"
                :disabled (str/blank? @text)
                :onClick commit}
           "Add"]]]))))

;; ---------------------------------------------------------------------------
;; Pinned queries

(defn- pinned-query-card [{:keys [domain-id name]}]
  (let [q (first (filter #(= name (:name %))
                         (get-in @app-state [:schema :queries])))
        result (when q (state/run-query (:text q)))
        d-label (some-> (state/domain-info domain-id) :label)
        cap 5]
    [:> (.-Card antd/antd)
     {:size "small"
          :hoverable true
          :style #js {:marginBottom 12}
          :title (r/as-element
                  [:span
                   [:a {:on-click #(do (some-> domain-id state/switch-domain!)
                                       (state/select! {:kind :query :name name}))
                        :style {:cursor "pointer"}}
                    name]
                   (when d-label
                     [:> (.-Tag antd/antd)
                      {:style #js {:marginLeft 8}} d-label])])
          :extra (r/as-element
                  (when-not (:error result)
                    [:> (.-Badge antd/antd)
                     {:count (count (:rows result))
                          :showZero true
                          :overflowCount 999
                          :style #js {:backgroundColor "var(--ant-color-primary)"}}]))}
     (cond
       (:error result)
       [:> (.-Alert antd/antd)
        {:type "error" :message (str "Error: " (:error result))}]

       (empty? (:rows result))
       [:> (.-Empty antd/antd)
        {:description "No solutions"
         :image (.-PRESENTED_IMAGE_SIMPLE (.-Empty antd/antd))}]

       :else
       (let [ctor-map (constructor-type-map)
             shown (take cap (:rows result))]
         [:div.pinned-results
          (for [[i row] (map-indexed vector shown)]
            ^{:key i}
            [:div.pinned-row
             (for [[k v] (map vector (:vars result) row)]
               ^{:key k}
               [:span.pinned-bind
                [:span.pinned-var k] " " [type-value ctor-map v]])])
          (when (> (count (:rows result)) cap)
            [:a.pinned-more
             {:on-click #(do (some-> domain-id state/switch-domain!)
                             (state/select! {:kind :query :name name}))}
             "+ " (- (count (:rows result)) cap) " more"])]))]))

(defn pinned-queries-section []
  (let [pins (state/pinned-queries)]
    (if (empty? pins)
      [:> (.-Empty antd/antd)
       {:description (r/as-element
                      [:span "No pinned queries. Open a saved query and click "
                       [:b "Pin to Home"] "."])
        :image (.-PRESENTED_IMAGE_SIMPLE (.-Empty antd/antd))}]
      [:div.pinned-grid
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
    (if (or (empty? domains) (empty? events))
      [:> (.-Empty antd/antd)
       {:description (if (empty? domains)
                       "Create a domain to start logging activity."
                       "No activity yet. Use the scratch above to add a fact.")
        :image (.-PRESENTED_IMAGE_SIMPLE (.-Empty antd/antd))}]
      [:> (.-List antd/antd)
       {:dataSource (clj->js (mapv (fn [{:keys [id at op triple source]}]
                                         (let [[e a v] triple
                                               dom (when (keyword? e)
                                                     (state/entity-domain e))
                                               dom-label (some-> dom
                                                                 state/domain-info
                                                                 :label)]
                                           #js {:id id
                                                :at at
                                                :op op
                                                :e e :a a :v v
                                                :dom dom
                                                :domLabel dom-label
                                                :source source}))
                                       events))
            :renderItem (fn [item]
                          (r/as-element
                           [:> (.-ListItem antd/antd)
                            {:style #js {:padding "8px 0"}}
                            [:div.activity-row
                             [:span.act-when {:title (.toLocaleString (js/Date. (.-at item)))}
                              (fmt-relative (.-at item))]
                             (when (.-domLabel item)
                               [:> (.-Tag antd/antd)
                                {:color "blue"
                                     :style #js {:cursor "pointer"}
                                     :onClick #(state/switch-domain! (.-dom item))}
                                (.-domLabel item)])
                             [:> (.-Tag antd/antd)
                              {:color (if (= "assert" (name (.-op item)))
                                            "success" "error")}
                              (if (= "assert" (name (.-op item))) "+" "−")]
                             [:span.act-triple
                              [:span.act-sub [atom-link (.-e item)]]
                              " " [pred-link (.-a item)] " "
                              [:span.act-obj [type-value ctor-map (.-v item)]]]
                             (when (.-source item)
                               [:span.act-src {:title (str "source: " (.-source item))}
                                (.-source item)])]]))
            :size "small"
            :split true}])))

;; ---------------------------------------------------------------------------
;; Top-level home view

(defn home-view []
  (let [s @app-state
        collapsed? (get-in s [:home :onboarding-collapsed?])
        domains (state/domains-list)]
    [:div.view.home-view
     ;; Hero section
     [:div.home-hero
      [:div.home-logo
       [:span.logo-big "G"]]
      [:div.home-hero-text
       [:> (.-TypographyTitle antd/antd) {:level 2 :style #js {:marginBottom 4}}
        "Golova"]
       [:> (.-TypographyText antd/antd)
        {:type "secondary"}
        "A small, no-server PKM built on Datahike. Triples, rules, derivations — your knowledge as a graph."]]]

     ;; Quick add + Activity (side by side)
     [:div.home-grid
      [:> (.-Card antd/antd)
       {:title (r/as-element [:span [:> (.-ThunderboltOutlined icons) " "] "Quick Add"])
            :size "small"
            :style #js {:height "100%"}}
       [quick-scratch]]

      [:> (.-Card antd/antd)
       {:title (r/as-element [:span [:> (.-RetweetOutlined icons) " "] "Recent Activity"])
            :size "small"
            :style #js {:height "100%"}
            :styles #js {:body #js {:maxHeight 340 :overflowY "auto"}}}
       [activity-feed]]]

     ;; Pinned queries
     [:> (.-Card antd/antd)
      {:title (r/as-element [:span [:> (.-StarOutlined icons) " "] "Pinned Queries"])
           :size "small"
           :style #js {:marginBottom 20}}
      [pinned-queries-section]]

     ;; Getting started (collapsible)
     [:> (.-Card antd/antd)
      {:title (r/as-element [:span [:> (.-InfoCircleOutlined icons) " "] "Getting Started"])
           :size "small"
           :style #js {:marginBottom 20}
           :extra (r/as-element
                   [:> (.-Button antd/antd)
                    {:type "text" :size "small"
                         :onClick #(state/toggle-onboarding!)}
                    (if collapsed? "Show" "Hide")])}
      (when-not collapsed?
        [:div.getting-started
         [:p "Golova represents your knowledge as " [:b "triples"] ": "
          [:code "[subject attribute value]"] ". The starter domain has "
          [:code "[alice :parent bob]"] " etc. — type facts in the predicate "
          "tables or via Cmd-K, define inference rules, and ask queries."]
         [glossary]
         [:div.shortcut-hints
          [:> (.-Space antd/antd) {:size "middle"}
           [:span [:kbd "⌘K"] " Palette"]
           [:span [:kbd "⌘↵"] " Save rules"]
           [:span [:kbd "Esc"] " Close"]]]])]

     ;; Domains grid
     [:> (.-Card antd/antd)
      {:title (r/as-element [:span [:> (.-FolderOutlined icons) " "] "Domains"])
           :size "small"
           :extra (r/as-element
                   [:> (.-Button antd/antd)
                    {:type "primary" :size "small"
                         :icon (r/as-element [:> (.-PlusOutlined icons)])
                         :onClick #(state/open-modal! {:kind :new-domain})}
                    "New Domain"])}
      [:div.domains-grid
       (doall
        (for [{:keys [id label]} domains]
          (let [n-facts (count (state/triples-in-domain id))
                n-preds (count (state/declared-predicates-in id))
                n-rules (count (state/rules-in id))]
            ^{:key id}
            [:> (.-Card antd/antd)
             {:size "small"
                  :hoverable true
                  :style #js {:marginBottom 12}
                  :onClick #(state/switch-domain! id)
                  :styles #js {:body #js {:padding "12px 16px"}}}
             [:div.domain-card-inner
              [:div.domain-card-name label]
              [:div.domain-card-stats
               [:> (.-Statistic antd/antd)
                {:title "facts" :value n-facts :styles #js {:content #js {:fontSize 16}}}]
               [:> (.-Statistic antd/antd)
                {:title "preds" :value n-preds :styles #js {:content #js {:fontSize 16}}}]
               [:> (.-Statistic antd/antd)
                {:title "rules" :value n-rules :styles #js {:content #js {:fontSize 16}}}]]]])))
       ;; New domain card
       [:> (.-Card antd/antd)
        {:size "small"
             :hoverable true
             :style #js {:marginBottom 12 :borderStyle "dashed"}
             :onClick #(state/open-modal! {:kind :new-domain})
             :styles #js {:body #js {:padding "12px 16px"
                                     :textAlign "center"}}}
        [:div.domain-card-inner
         [:div.domain-card-name {:style {:color "var(--ant-color-text-secondary)"}}
          "+ New domain"]
         [:div {:style {:color "var(--ant-color-text-tertiary)"}} "Empty"]]]]]]))
