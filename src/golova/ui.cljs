(ns golova.ui
  "Reagent views for Golova. Each top-level surface is a function returning
  hiccup; the active one is chosen by `:selection` in app-state."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [naga.store :as nstore]
            [golova.state :as state :refer [app-state]]
            [golova.storage :as storage]))

(def ^:private result-row-cap 200)

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
  "Return seq of {:name k :arity n :facts [...]} for every namespace-bare
  attribute appearing in the domain's store."
  [domain]
  (let [store (:store domain)
        triples (when store
                  (try (nstore/resolve-pattern store '[?e ?a ?v]) (catch :default _ [])))
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
     ;; discovered predicates from the store
     (for [a own-attrs
           :when (not (contains? declared (name a)))]
       {:name (name a)
        :namespace (namespace a)
        :arity 2     ;; flat triples represent 2-arity
        :declared? false
        :facts (vec (filter #(= a (second %)) triples))}))))

(defn- domain-rules
  "Return seq of {:name n :arity a :clauses [rule …]} per rule-head, grouped
  so that multi-clause rules (e.g. base + recursive ancestor) appear as one
  sidebar entry. Arity is user-visible (head args), not the triple-shape (E A V)."
  [domain]
  (->> (:rules domain)
       (keep (fn [r]
               (let [head (first (:head r))
                     head-attr (when (>= (count head) 2) (second head))]
                 (when (keyword? head-attr)
                   {:name  (name head-attr)
                    :arity (max 0 (dec (count head)))
                    :rule  r}))))
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
  "Header row for a subsection within an expanded domain block. The caller
  renders the items themselves immediately after."
  [label items add-fn]
  (let [n (count items)]
    [:div.subsection {:class (when (zero? n) "empty")}
     [:span.lbl label]
     [:span.scount n]
     (when add-fn
       [:button.sub-add
        {:title (str "Add " label)
         :on-click (fn [e] (.stopPropagation e) (add-fn))} "+"])]))

(defn- nav-item [{:keys [active? icon label meta on-click]}]
  [:div.nav-item {:class (when active? "active")
                  :on-click on-click}
   [:span.icon icon]
   [:span.name.mono label]
   (when meta [:span.meta meta])])

(defn sidebar []
  (let [{:keys [domains current-domain selection expanded]} @app-state]
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
           [:div.domain-body
            (sub "Types" types
                 (fn [] (state/open-modal! {:kind :new-type :domain id})))
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
                               (state/select! {:kind :type :name (:name t)}))}])
            (sub "Predicates" (concat decl-pred disc-pred)
                 (fn [] (state/open-modal! {:kind :new-predicate :domain id})))
            (for [p (concat decl-pred disc-pred)]
              ^{:key (str "p-" (:name p) "/" (:arity p))}
              [nav-item
               {:active? (and active-domain?
                              (= :predicate (:kind selection))
                              (= (:name p) (:name selection))
                              (= (:arity p) (:arity selection)))
                :icon (if (:declared? p) "▦" "▢")
                :label (str (:name p) "/" (:arity p))
                :meta (count (:facts p))
                :on-click #(do (state/switch-domain! id)
                               (state/select! {:kind :predicate
                                               :name (:name p)
                                               :arity (:arity p)}))}])
            (sub "Rules" rules
                 (fn [] (state/open-modal! {:kind :new-rule :domain id})))
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
                               (state/select! {:kind :rule :name (:name r)}))}])
            (sub "Queries" queries
                 (fn [] (state/open-modal! {:kind :new-query :domain id})))
            (for [q queries]
              ^{:key (str "q-" (:name q))}
              [nav-item
               {:active? (and active-domain?
                              (= :query (:kind selection))
                              (= (:name q) (:name selection)))
                :icon "?"
                :label (:name q)
                :on-click #(do (state/switch-domain! id)
                               (state/select! {:kind :query :name (:name q)}))}])
            [nav-item
             {:active? (and active-domain? (= :scratch (:kind selection)))
              :icon "✎"
              :label "Scratch"
              :on-click #(do (state/switch-domain! id)
                             (state/select! {:kind :scratch}))}]])])]

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
;; Scratch (program editor)

(def ^:private scratch-ta-id "scratch-ta")

(defn- slash-anchor
  "Compute a screen anchor for the slash popover from the textarea + cursor.
  Approximate — assumes the monospace font from `--code-font` at 13.5px."
  [ta pos]
  (let [r          (.getBoundingClientRect ta)
        v          (.-value ta)
        before     (subs v 0 pos)
        lines      (str/split before #"\n" -1)
        row        (dec (count lines))
        col        (count (last lines))
        line-h     21
        char-w     8.1
        pad        12
        scroll-top (.-scrollTop ta)]
    {:left (+ (.-left r) pad (* col char-w))
     :top  (+ (.-top r) pad (- (* (inc row) line-h) scroll-top))}))

(defn- open-slash-menu! [ta local]
  (let [pos (.-selectionStart ta)
        v (.-value ta)
        at-start (or (zero? pos) (= "\n" (.charAt v (dec pos))))]
    (when at-start
      (swap! app-state assoc :popover
             {:kind :slash
              :anchor (slash-anchor ta pos)
              :ta-id scratch-ta-id
              :local local
              :slash-pos pos})
      true)))

(defn- insert-slash-template! [tpl-key]
  (let [{:keys [local slash-pos ta-id]} (:popover @app-state)
        ;; [template, select-start-rel, select-end-rel]
        tpls {:fact    ["predicate(arg1, arg2).\n" 0 9]
              :rule    ["head(X, Y) :- body(X, Y).\n" 0 4]
              :comment ["% comment\n" 2 9]
              :heading ["%% -- SECTION --\n" 5 14]}
        [tpl ss se] (get tpls tpl-key)
        v (:text @local)
        new-v (str (subs v 0 slash-pos) tpl (subs v slash-pos))]
    (swap! local assoc :text new-v :saved? false)
    (close-popover!)
    (js/setTimeout
     (fn []
       (when-let [ta (.getElementById js/document ta-id)]
         (.focus ta)
         (set! (.-selectionStart ta) (+ slash-pos ss))
         (set! (.-selectionEnd   ta) (+ slash-pos se))))
     20)))

(defn scratch-view []
  (let [{:keys [current-domain]} @app-state
        domain (state/current)
        local (r/atom {:text (:program-text domain) :saved? true :err nil})]
    (fn []
      (let [d (state/current)
            current-text (:program-text d)
            err (:build-error d)]
        ;; if external program-text changed (e.g. domain switch), reset local
        (when (not= current-text (:loaded @local))
          (reset! local {:text current-text :loaded current-text :saved? true :err nil}))
        [:div.view
         [:div.view-head
          [:h2 "Scratch"]
          [:span.desc "Edit the Naga program for "
           [:b (:label d)] ". Facts and rules are the source of truth."
           [:span.kbd-hint "  · type "
            [:kbd "/"] " at the start of a line for templates"]]
          [:div.actions-right
           [:button.primary
            {:on-click (fn []
                         (state/set-program! current-domain (:text @local))
                         (swap! local assoc :saved? true))}
            "Rebuild"]]]
         [:div.program-editor {:class (cond err "err"
                                            (not (:saved? @local)) "dirty")}
          [:textarea
           {:id scratch-ta-id
            :spellCheck "false"
            :value (:text @local)
            :on-change #(swap! local assoc :text (.. % -target -value) :saved? false)
            :on-key-down (fn [e]
                           (cond
                             (and (or (.-metaKey e) (.-ctrlKey e))
                                  (= "Enter" (.-key e)))
                             (do (.preventDefault e)
                                 (state/set-program! current-domain (:text @local))
                                 (swap! local assoc :saved? true))

                             (and (= "/" (.-key e))
                                  (not (.-metaKey e))
                                  (not (.-ctrlKey e))
                                  (not (.-altKey e)))
                             (when (open-slash-menu! (.-target e) local)
                               (.preventDefault e))))}]
          [:div.footer
           [:span.status {:class (cond err "err"
                                       (:saved? @local) "ok"
                                       :else "dirty")}
            (cond err (str "build error: " err)
                  (:saved? @local) "saved"
                  :else "edited — press Rebuild")]]]
         (when (:store d)
           (let [triples (state/all-triples current-domain)
                 total (count triples)
                 shown (take 200 triples)
                 retract!
                 (fn [tr prov]
                   (let [warn (when (= prov :program)
                                "This fact is in the program text. Retracting only adds an event that suppresses it on rebuild — if you edit the program later, the same fact may reappear.\n\nContinue?")]
                     (when (or (not warn) (js/confirm warn))
                       (state/retract-triple! current-domain (vec tr)))))]
             [:div {:style {:margin-top "24px"}}
              [:h3.materialized-h
               "Materialized triples"
               [:span.count-hint total " total"
                (when (> total 200) (str " · showing first 200"))]]
              [:table.facts
               [:thead [:tr [:th "subject"] [:th "predicate"] [:th "object"]
                        [:th "source"] [:th ""]]]
               [:tbody
                (for [[i [e a v :as tr]] (map-indexed vector shown)]
                  (let [prov (state/triple-provenance current-domain tr)]
                    ^{:key i}
                    [:tr
                     [:td (atom-link e)]
                     [:td [:span.mono (fmt-val a)]]
                     [:td (atom-link v)]
                     [:td [:span.pill {:class (str "prov-" (name prov))} (name prov)]]
                     [:td.delete
                      (when (not= :derived prov)
                        [:button.ghost.danger
                         {:title (case prov
                                   :event "Retract this event"
                                   :imported "Retract imported triple"
                                   :program "Suppress this program axiom")
                          :on-click #(retract! tr prov)} "×"])]]))]]]))]))))

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
        edit (r/atom nil)]                              ;; nil | {:idx i :st {:val ...}}
    (fn [_ arg-types triple]
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
                      (atom-link orig)]))]
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

(defn predicate-view [name arity]
  (let [domain-id (state/current-id)
        d (state/current)
        attr (keyword name)
        triples (filter #(= attr (second %)) (state/all-triples domain-id))
        declared (first (filter #(and (= name (:name %))
                                      (= arity (count (:argTypes %))))
                                (get-in d [:schema :predicates])))
        arg-types (when declared (:argTypes declared))]
    [:div.view
     [:div.view-head
      [:h2.mono (str name "/" arity)]
      [:span.desc (count triples) " fact" (when (not= 1 (count triples)) "s")]
      (if declared
        [:span.pill.declared "declared"]
        [:span.pill "discovered"])
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
                         (state/select! {:kind :scratch})))}
          "Delete declaration"])]]
     [:table.facts
      [:thead
       [:tr
        (if declared
          (for [[i t] (map-indexed vector arg-types)]
            ^{:key i}
            [:th [:div.h
                  [:span.type-icon {:title t} (type-icon-for t)]
                  [:span t]]])
          [:<> [:th [:div.h [:span.type-icon "◇"] [:span "subject"]]]
               [:th [:div.h [:span.type-icon "◇"] [:span "object"]]]])
        [:th ""]]]
      [:tbody
       (if (empty? triples)
         [:tr [:td {:col-span (inc (or (count arg-types) 2))
                    :style {:padding "24px" :text-align "center" :color "var(--muted)"
                            :font-family "var(--sans-font)"}}
               "No facts yet — use the row below to add one."]]
         (for [[i tr] (map-indexed vector triples)]
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
         "(Arity " (count arg-types) " requires entity reification.)"]])]))

;; ---------------------------------------------------------------------------
;; Type view

(defn type-view [name]
  (let [domain-id (state/current-id)
        d (state/current)
        t (first (filter #(= name (:name %)) (get-in d [:schema :types])))
        new-ctor (r/atom "")]
    (fn [name]
      (let [domain-id (state/current-id)
            d (state/current)
            t (first (filter #(= name (:name %)) (get-in d [:schema :types])))]
        (if-not t
          [:div.view [:div.empty [:h3 "Type not found"]]]
          [:div.view
           [:div.view-head
            [:h2.mono name]
            [:span.desc (count (:constructors t)) " value"
             (when (not= 1 (count (:constructors t))) "s")]
            [:span.pill.declared "type"]
            [move-to-pill domain-id
             (fn [src dst] (state/move-type! src dst name))]
            [:div.actions-right
             [:button.ghost.danger
              {:on-click (fn []
                           (when (js/confirm (str "Delete type " name "?"))
                             (state/delete-type! domain-id name)
                             (state/select! {:kind :scratch})))}
              "Delete type"]]]
           [:div.constructor-list
            (for [c (:constructors t)]
              ^{:key c}
              [:div.nav-item {:style {:padding-left 0}}
               [:span.icon "◇"]
               [:span.name [:a.atom-link
                            {:on-click #(state/select! {:kind :entity
                                                        :name (keyword c)})}
                            c]]])
            [:div.add-ctor
             [:input {:placeholder "+ new constructor"
                      :value @new-ctor
                      :on-change #(reset! new-ctor (.. % -target -value))
                      :on-key-down (fn [e]
                                     (when (= "Enter" (.-key e))
                                       (.preventDefault e)
                                       (state/extend-type! domain-id name @new-ctor)
                                       (reset! new-ctor "")))}]]]])))))

;; ---------------------------------------------------------------------------
;; Rule view

;; --- Rule pretty-printing (Naga structs → Pabu-ish text) -------------------

(defn- fmt-rule-arg [v]
  (cond
    (symbol? v)  (str v)
    (keyword? v) (fmt-val v)
    (string? v)  (str "\"" v "\"")
    :else        (str v)))

(defn- fmt-pred-name [a]
  (if (keyword? a)
    (if-let [n (namespace a)] (str n "." (name a)) (name a))
    (str a)))

(defn- fmt-rule-pattern [p]
  (cond
    (vector? p)
    (let [[e a v] p]
      (str (fmt-pred-name a) "(" (fmt-rule-arg e) ", " (fmt-rule-arg v) ")"))
    :else (pr-str p)))

(defn- fmt-rule
  "Format a Naga rule record as readable Pabu-ish text."
  [r]
  (let [head (str/join ", " (map fmt-rule-pattern (:head r)))
        body (str/join ", " (map fmt-rule-pattern (:body r)))]
    (str head " :- " body ".")))

(defn- rule-clause [r]
  (letfn [(hl-args [s]
            ;; render `?x` variables in accent color by splitting on whitespace
            ;; and emitting spans
            (let [parts (re-seq #"\?[A-Za-z][A-Za-z0-9_]*|[^?]+|\?" s)]
              (for [[i p] (map-indexed vector parts)]
                (if (and (> (count p) 1) (= "?" (subs p 0 1)))
                  ^{:key i} [:span.var p]
                  ^{:key i} [:span p]))))]
    (let [head-str (str/join ", " (map fmt-rule-pattern (:head r)))
          body-str (str/join ", " (map fmt-rule-pattern (:body r)))]
      [:div.rule-clause
       [:div.rule-head (hl-args head-str)]
       [:div.rule-arrow ":-"]
       [:div.rule-body (hl-args body-str) [:span.dot "."]]])))

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
        {:on-click #(state/select! {:kind :scratch})}
        "Edit in Scratch"]]]
     (for [[i r] (map-indexed vector clauses)]
       ^{:key i}
       [:div.rule-card
        [rule-clause r]])
     [:div.hint
      "Rules are defined in the domain's Scratch (program text). Edit there to change."]]))

;; ---------------------------------------------------------------------------
;; Query view (saved query)

(defn query-view [name]
  (let [d (state/current)
        q (first (filter #(= name (:name %)) (get-in d [:schema :queries])))
        local (r/atom {:text (:text q) :result nil})]
    (fn []
      (let [domain-id (state/current-id)
            d (state/current)
            q (first (filter #(= name (:name %)) (get-in d [:schema :queries])))]
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
                             (state/select! {:kind :scratch})))} "Delete"]]]
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

(defn- relation-form-block
  [domain-id entity attr arg-types roles]
  [:div.add-zone
   (for [role (sort roles)]
     ^{:key role}
     [entity-add-form domain-id entity attr role arg-types])])

(defn entity-view [e]
  (let [open-add (r/atom nil)] ;; nil | {:name "..." :arg-types [...] :role :subject}
    (fn [e]
      (let [domain-id (state/current-id)
            d (state/current)
            triples (state/entity-mentions domain-id e)
            by-attr (group-by second triples)
            declared-preds (->> (get-in d [:schema :predicates])
                                (filter #(= 2 (count (:argTypes %)))))
            seen-attrs (set (keys by-attr))
            unseen-preds (->> declared-preds
                              (remove #(seen-attrs (keyword (:name %)))))]
        [:div.view
         [:div.entity-hero
          [:div.avatar (str/upper-case (subs (fmt-val e) 0 1))]
          [:div
           [:div.mono.title (fmt-val e)]
           [:div.subtitle (count triples) " mention"
            (when (not= 1 (count triples)) "s")]]]

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
                   roles (entity-relation-roles e trs)]
               [:div.relation-group
                [:h3.rel-attr
                 [:span.rel-name (fmt-val attr)]
                 [:span.dim " — " (count trs)]
                 (when decl [:span.pill.declared.mini "declared"])]
                (for [[i [ee _ vv :as tr]] (map-indexed vector trs)]
                  ^{:key (str (pr-str tr))}
                  [:div.fact-row
                   [:span (atom-link ee)]
                   [:span.arrow "→"]
                   [:span (atom-link vv)]
                   [:button.ghost.danger.remove
                    {:on-click #(state/retract-triple! domain-id (vec tr))
                     :title "Retract"} "×"]])
                [relation-form-block domain-id e attr arg-types roles]])))

         ;; Add another relation
         (when (seq declared-preds)
           [:div.new-relation
            [:h3.section-h "Add another relation"]
            [:div.pred-picker
             (for [p unseen-preds]
               ^{:key (:name p)}
               [:button.chip
                {:class (when (and @open-add (= (:name p) (:name @open-add))) "active")
                 :on-click #(reset! open-add
                                    {:name (:name p) :arg-types (:argTypes p)
                                     :role :subject})}
                (:name p) "/" (count (:argTypes p))])
             (when (empty? unseen-preds)
               [:span.dim {:style {:padding "4px 0"}}
                "All declared predicates already have facts for this entity."])]
            (when-let [{:keys [name arg-types role]} @open-add]
              [:div.add-inline
               [:div.role-toggle
                [:button {:class (when (= role :subject) "active")
                          :on-click #(swap! open-add assoc :role :subject)}
                 "as subject"]
                [:button {:class (when (= role :object) "active")
                          :on-click #(swap! open-add assoc :role :object)}
                 "as object"]
                [:button.ghost {:on-click #(reset! open-add nil)} "Cancel"]]
               [entity-add-form domain-id e (keyword name) role arg-types]])])]))))

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
          [:<>
           [:h3 "New type"]
           [:div.modal-sub "A named union of values, e.g. " [:code "person ::= alice | bob"]]
           [text-field {:label "Name" :id "type-name" :placeholder "e.g. person"}]
           [text-field {:label "Constructors (comma-separated)" :id "type-ctors"
                        :placeholder "e.g. alice, bob, carol"}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (let [name (read-field "type-name")
                                ctors (->> (read-field "type-ctors")
                                           (#(str/split (or % "") #","))
                                           (map str/trim)
                                           (remove str/blank?))]
                            (when (seq name)
                              (state/declare-type! (:domain m) name (vec ctors))
                              (state/select! {:kind :type :name name}))
                            (state/close-modal!)))}
             "Create"]]]

          :new-predicate
          [:<>
           [:h3 "New predicate"]
           [:div.modal-sub "A typed relation. Args may be declared types or "
            [:code "int"] " / " [:code "string"] " / " [:code "atom"] "."]
           [text-field {:label "Name" :id "pred-name" :placeholder "e.g. age"
                        :default (:preset-name m)}]
           [text-field {:label "Arg types (comma-separated)" :id "pred-types"
                        :placeholder "e.g. person, int"}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (let [name (read-field "pred-name")
                                types (->> (read-field "pred-types")
                                           (#(str/split (or % "") #","))
                                           (map str/trim)
                                           (remove str/blank?))]
                            (when (and (seq name) (seq types))
                              (state/declare-predicate! (:domain m) name (vec types))
                              (state/select! {:kind :predicate :name name
                                              :arity (count types)}))
                            (state/close-modal!)))}
             "Declare"]]]

          :new-rule
          [:<>
           [:h3 "New rule"]
           [:div.modal-sub "Appended to the domain's program text."]
           [text-field {:label "Rule (Naga / Pabu syntax)" :id "rule-text"
                        :placeholder "head(X, Y) :- body1(X), body2(Y)."
                        :rows 4}]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Cancel"]
            [:button.primary
             {:on-click (fn []
                          (let [rule (read-field "rule-text")
                                d (get-in @app-state [:domains (:domain m)])
                                current (:program-text d)
                                joined (if (str/blank? current)
                                         (str rule "\n")
                                         (str (str/trimr current) "\n" rule "\n"))]
                            (when (seq rule)
                              (state/set-program! (:domain m) joined)
                              (state/select! {:kind :scratch}))
                            (state/close-modal!)))}
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
  "Set of keyword entities in a domain's store + type constructors."
  [d]
  (let [store (:store d)
        in-store (when store
                   (->> (try (nstore/resolve-pattern store '[?e ?a ?v])
                             (catch :default _ []))
                        (mapcat (fn [[e _ v]] [e v]))
                        (filter keyword?)
                        set))
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
        {:kind :cmd :label (str "Open scratch for " (:label d)) :icon "✎"
         :run (nav {:kind :scratch})}])
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

         :slash
         [popover-shell (:anchor p)
          [:div.popover-card.slash
           [:div.popover-title "Insert"]
           [:button.popover-item
            {:on-click #(insert-slash-template! :fact)}
            [:span.k "·"] " Fact "
            [:span.cmd-hint "predicate(a, b)."]]
           [:button.popover-item
            {:on-click #(insert-slash-template! :rule)}
            [:span.k "ƒ"] " Rule "
            [:span.cmd-hint "head :- body."]]
           [:button.popover-item
            {:on-click #(insert-slash-template! :comment)}
            [:span.k "%"] " Comment "
            [:span.cmd-hint "% comment"]]
           [:button.popover-item
            {:on-click #(insert-slash-template! :heading)}
            [:span.k "§"] " Section heading "
            [:span.cmd-hint "%% -- SECTION --"]]]]

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
    (cond
      (nil? (:current-domain s))
      [:div.view [:div.empty
                  [:h3 "Welcome to Golova"]
                  [:p "Create a domain to begin."]
                  [:button.primary
                   {:on-click #(state/open-modal! {:kind :new-domain})}
                   "+ New domain"]]]

      :else
      (case (:kind sel)
        :type      [type-view (:name sel)]
        :predicate [predicate-view (:name sel) (:arity sel)]
        :rule      [rule-view (:name sel)]
        :query     [query-view (:name sel)]
        :entity    [entity-view (:name sel)]
        [scratch-view]))))

(defn root []
  [:<>
   [sidebar]
   [:main
    [topbar]
    [main]]
   [modal]
   [popover]
   [palette]])
