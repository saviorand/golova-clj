(ns golova.ui.palette
  "Command palette (⌘K). Fuzzy-search candidate list: commands, domains,
  types, predicates, rules, queries, and entities — current-domain content
  surfaces first."
  (:require [clojure.string :as str]
            [golova.state :as state :refer [app-state]]
            [golova.ui.common :refer [fmt-val]]
            [golova.ui.derivations :refer [domain-predicates domain-rules]]))

(defn- domain-entities
  "Set of keyword entities in a domain — atoms + declared type constructors."
  [domain-id]
  (let [in-store (set (state/atoms-in-domain domain-id))
        ctors (->> (state/declared-types-in domain-id)
                   (mapcat (fn [t] (map keyword (:constructors t))))
                   set)]
    (into (or in-store #{}) ctors)))

(defn- palette-candidates [_state]
  (let [cur-id (state/current-id)
        cur (some-> cur-id state/domain-info)
        d-label (:label cur)
        doms (state/domains-list)
        nav    (fn [sel] #(do (state/close-palette!) (state/select! sel)))
        modal  (fn [m]   #(do (state/close-palette!) (state/open-modal! m)))]
    (concat
     [{:kind :cmd :label "New domain…" :icon "+"
       :run (modal {:kind :new-domain})}
      {:kind :cmd :label "Open settings" :icon "⚙"
       :run (modal {:kind :settings})}]
     (when cur-id
       [{:kind :cmd :label (str "New type in " d-label "…") :icon "◆"
         :run (modal {:kind :new-type :domain cur-id})}
        {:kind :cmd :label (str "New predicate in " d-label "…") :icon "▦"
         :run (modal {:kind :new-predicate :domain cur-id})}
        {:kind :cmd :label (str "New rule in " d-label "…") :icon "ƒ"
         :run (modal {:kind :new-rule :domain cur-id})}
        {:kind :cmd :label (str "Save query in " d-label "…") :icon "?"
         :run (modal {:kind :new-query :domain cur-id})}
        {:kind :cmd :label (str "Open rules for " d-label) :icon "ƒ"
         :run (nav {:kind :rules})}])
     (for [{:keys [id label]} doms]
       {:kind :domain :label label :sublabel "domain" :icon "□"
        :run #(do (state/close-palette!) (state/switch-domain! id))})
     (when cur-id
       (concat
        (for [t (state/declared-types-in cur-id)]
          {:kind :type :label (:name t) :sublabel "type" :icon "◆"
           :run (nav {:kind :type :name (:name t)})})
        (for [p (domain-predicates cur-id)]
          {:kind :pred
           :label (str (:name p) "/" (:arity p))
           :sublabel (if (:declared? p) "predicate" "predicate (discovered)")
           :icon (if (:declared? p) "▦" "▢")
           :run (nav {:kind :predicate :name (:name p) :arity (:arity p)})})
        (for [r (domain-rules cur-id)]
          {:kind :rule
           :label (str (:name r) "/" (:arity r))
           :sublabel "rule"
           :icon "ƒ"
           :run (nav {:kind :rule :name (:name r)})})
        (for [q (state/queries-in cur-id)]
          {:kind :query :label (:name q) :sublabel "saved query" :icon "?"
           :run (nav {:kind :query :name (:name q)})})
        (for [e (sort-by str (domain-entities cur-id))]
          {:kind :entity :label (fmt-val e) :sublabel "entity" :icon "◇"
           :run (nav {:kind :entity :name e})}))))))

(defn- match-score
  "Positive score for matching candidates, nil to drop. Prefix beats substring."
  [q label]
  (let [lab (str/lower-case label)
        q   (str/lower-case (str/trim q))]
    (cond
      (str/blank? q)              0
      (str/starts-with? lab q)    (- 200 (count label))
      (str/includes? lab q)       (- 100 (count label))
      :else                       nil)))

(defn- kind-rank [k]
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
