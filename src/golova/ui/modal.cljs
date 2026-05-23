(ns golova.ui.modal
  "All modal dialogs: simple new-* forms (text-field/read-field pattern),
  bigger form-2 components for new-predicate / new-type / csv-import /
  change-pred-type, plus the central :kind dispatch."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.storage :as storage]
            [golova.csv :as csv]
            [golova.ui.typed :refer [chip-editor]]))

(declare sync-settings-section)

;; ---------------------------------------------------------------------------
;; Tiny text-input helpers (DOM-id-based, used by the small modal forms)

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

;; ---------------------------------------------------------------------------
;; New predicate form (per-arg type dropdowns)

(defn- arg-type-options [_domain-id]
  (let [declared (->> (get-in @app-state [:schema :types])
                      (map :name)
                      sort)]
    (vec (concat ["atom" "int" "string"] declared))))

(defn new-predicate-form
  "Form-2 component: dynamic list of arg-type dropdowns."
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

;; ---------------------------------------------------------------------------
;; New type form

(defn new-type-form
  "Form-2 component: name + chip-editor for constructors."
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

;; ---------------------------------------------------------------------------
;; CSV import preview / confirm

(defn- default-id-col [{:keys [headers]}]
  (when (seq headers) 0))

(defn csv-import-form
  "Modal body for previewing and confirming a CSV import."
  [{:keys [data filename]}]
  (let [preview (state/csv-preview data)
        new-label  (-> (or filename "imported")
                       (str/replace #"\.csv$" "")
                       (str/replace #"[_-]+" " ")
                       str/trim)
        dom*    (r/atom :__new__)
        new-dom (r/atom new-label)
        id-idx* (r/atom (default-id-col data))
        skip-empty?* (r/atom true)
        msg     (r/atom nil)]
    (fn [{:keys [data]}]
      (let [domains (state/domains-list)
            domain-by-id (into {} (map (juxt :id identity) domains))
            id-idx @id-idx*
            id-col (get (:columns preview) id-idx)
            empty-col-idxs (set (for [[i c] (map-indexed vector (:columns preview))
                                      :when (and (not= i id-idx)
                                                 (zero? (:non-empty c)))]
                                  i))
            n-empty-cols (count empty-col-idxs)
            drop-empty? (and @skip-empty?* (pos? n-empty-cols))
            n-preds (- (dec (:col-count preview))
                       (if drop-empty? n-empty-cols 0))
            keep-rows (filterv #(not (str/blank? (str/trim (nth % id-idx ""))))
                               (:rows data))
            n-entities (count (distinct
                                (map #(str/trim (nth % id-idx "")) keep-rows)))
            n-triples (reduce + 0
                              (for [row keep-rows
                                    [i v] (map-indexed vector row)
                                    :when (and (not= i id-idx)
                                               (not (str/blank? v))
                                               (not (and drop-empty?
                                                         (contains? empty-col-idxs i))))]
                                1))
            n-skipped (- (count (:rows data)) (count keep-rows))
            create-new? (= :__new__ @dom*)
            valid? (or (and (not create-new?) (contains? domain-by-id @dom*))
                       (and create-new? (seq (str/trim @new-dom))))]
        [:<>
         [:h3 "Import CSV"]
         [:div.modal-sub
          (if filename [:span [:code filename] " — "])
          (:row-count preview) " rows · "
          (:col-count preview) " columns"]

         [:div.field
          [:label "Target domain"]
          [:select.typed
           {:value (if create-new? "__new__" (name @dom*))
            :on-change (fn [e]
                         (let [v (.. e -target -value)]
                           (reset! dom* (if (= "__new__" v) :__new__ (keyword v)))))}
           (for [{:keys [id label]} domains]
             ^{:key id} [:option {:value (name id)} label])
           [:option {:value "__new__"}
            (str "+ New domain: " new-label)]]
          (when create-new?
            [:input {:placeholder "domain name"
                     :value @new-dom
                     :on-change #(reset! new-dom (.. % -target -value))
                     :style {:margin-top "6px"}}])]

         [:div.field
          [:label "Identity column"]
          [:select.typed
           {:value (str id-idx)
            :on-change #(reset! id-idx* (js/parseInt (.. % -target -value) 10))}
           (for [[i c] (map-indexed vector (:columns preview))]
             ^{:key i}
             [:option {:value (str i)}
              (str (:name c) "  (" (:non-empty c) " non-empty, "
                   (count (distinct (map #(str/trim (nth % i ""))
                                         (:rows data)))) " distinct)")])]
          [:div.modal-sub {:style {:margin-top "4px"}}
           "Each row's "
           [:code (:name id-col)] " becomes an atom keyword (slugified). "
           "Rows with blank value here are skipped."]]

         (when (pos? n-empty-cols)
           [:div.field
            [:label {:style {:display "flex" :align-items "center" :gap "8px"
                             :cursor "pointer" :text-transform "none"
                             :letter-spacing "0" :font-size "13px"
                             :font-weight "normal" :color "var(--text)"}}
             [:input {:type "checkbox"
                      :checked @skip-empty?*
                      :on-change #(reset! skip-empty?* (.. % -target -checked))}]
             (str "Skip the " n-empty-cols " column"
                  (when (not= 1 n-empty-cols) "s")
                  " with no values")]])

         [:div.modal-sub {:style {:margin-top "10px"}}
          "Will create "
          [:b n-preds] " predicate" (when (not= 1 n-preds) "s") ", "
          [:b n-entities] " entit" (if (= 1 n-entities) "y" "ies") ", and "
          [:b n-triples] " triple" (when (not= 1 n-triples) "s") "."
          (when (pos? n-skipped)
            [:span " " [:b n-skipped] " row"
             (when (not= 1 n-skipped) "s") " skipped (blank "
             [:code (:name id-col)] ")."])]

         (when @msg
           [:div.modal-sub
            {:style {:color (case (:kind @msg) :err "var(--bad)" "var(--good)")}}
            (:text @msg)])

         [:div.modal-actions
          [:button {:on-click state/close-modal!} "Cancel"]
          [:button.primary
           {:disabled (not valid?)
            :on-click (fn []
                        (let [[data* id-idx*]
                              (if drop-empty?
                                (let [keep-idxs (vec (remove empty-col-idxs
                                                             (range (count (:headers data)))))
                                      idx->new (zipmap keep-idxs (range))]
                                  [{:headers (mapv #(nth (:headers data) %) keep-idxs)
                                    :rows (mapv (fn [r] (mapv #(nth r % "") keep-idxs))
                                                (:rows data))}
                                   (idx->new id-idx)])
                                [data id-idx])
                              target (if create-new?
                                       (state/create-domain! (str/trim @new-dom))
                                       @dom*)
                              result (state/import-csv! target data* id-idx*)]
                          (state/select! {:kind :rules})
                          (state/switch-domain! target)
                          (state/close-modal!)
                          (js/console.log
                            "imported CSV:" (clj->js result))))}
           "Import"]]]))))

;; ---------------------------------------------------------------------------
;; Change-predicate-type form

(defn change-pred-type-form
  "Modal for converting a scalar predicate to refs."
  [{:keys [pred-name pred-arity]}]
  (let [pred (first (filter #(and (= pred-name (:name %))
                                  (= pred-arity (count (:argTypes %))))
                            (get-in @app-state [:schema :predicates])))
        source-dom (:domain pred)
        domains (state/domains-list)
        suggested (-> pred-name
                      (str/replace #"-" " ")
                      str/trim
                      (str/replace #"^." str/upper-case))
        target* (r/atom :__new__)
        new-dom (r/atom suggested)
        make-enum?* (r/atom true)
        enum-name (r/atom pred-name)]
    (fn [_]
      (let [t @target*
            new? (= :__new__ t)
            valid? (and (or (and (not new?) (some #(= t (:id %)) domains))
                            (and new? (seq (str/trim @new-dom))))
                        (or (not @make-enum?*)
                            (seq (str/trim @enum-name))))
            attr (keyword pred-name)
            unique-values (->> (:events @app-state)
                               (filter #(and (= :assert (:op %))
                                             (= attr (keyword (second (:triple %))))))
                               (map (comp #(nth % 2) :triple))
                               (map (fn [v] (if (string? v) (str/trim v) v)))
                               (remove #(or (nil? %)
                                            (and (string? %) (str/blank? %))))
                               distinct
                               count)]
        [:<>
         [:h3 "Change type"]
         [:div.modal-sub
          "Convert " [:code (str ":" pred-name)] "'s values into refs. "
          [:b unique-values] " unique value"
          (when (not= 1 unique-values) "s")
          " will become atoms in the target domain."]

         [:div.field
          [:label "Target domain"]
          [:select.typed
           {:value (if new? "__new__" (name t))
            :on-change (fn [e]
                         (let [v (.. e -target -value)]
                           (reset! target* (if (= "__new__" v)
                                             :__new__ (keyword v)))))}
           (for [{:keys [id label]} domains]
             ^{:key id} [:option {:value (name id)} label])
           [:option {:value "__new__"} (str "+ New domain: " suggested)]]
          (when new?
            [:input {:placeholder "domain name"
                     :value @new-dom
                     :on-change #(reset! new-dom (.. % -target -value))
                     :style {:margin-top "6px"}}])]

         [:div.field
          [:label {:style {:display "flex" :align-items "center" :gap "8px"
                           :cursor "pointer" :text-transform "none"
                           :letter-spacing "0" :font-size "13px"
                           :font-weight "normal" :color "var(--text)"}}
           [:input {:type "checkbox"
                    :checked @make-enum?*
                    :on-change #(reset! make-enum?* (.. % -target -checked))}]
           "Also declare an enum type grouping the new atoms"]
          (when @make-enum?*
            [:input {:placeholder "enum type name (e.g. organization)"
                     :value @enum-name
                     :on-change #(reset! enum-name (.. % -target -value))
                     :style {:margin-top "6px"}}])]

         [:div.modal-actions
          [:button {:on-click state/close-modal!} "Cancel"]
          [:button.primary
           {:disabled (not valid?)
            :on-click (fn []
                        (let [dom (if new?
                                    (state/create-domain! (str/trim @new-dom))
                                    t)
                              result (state/convert-predicate-to-refs!
                                       {:attr (keyword pred-name)
                                        :target-domain dom
                                        :enum-type-name
                                          (when @make-enum?*
                                            (str/trim @enum-name))
                                        :source-pred-domain source-dom})]
                          (js/console.log "converted:" (clj->js result))
                          (state/close-modal!)))}
           "Convert"]]]))))

;; ---------------------------------------------------------------------------
;; Sync settings (Settings modal section)

(defn sync-settings-section
  "Form-2 component: backend radio (Local / Git), Worker URL, bearer token,
  branch, and a Sync-now action. Persists to localStorage on Save and
  rebinds the visibility triggers."
  []
  (let [initial  (or (:sync-config @app-state) {})
        kind*    (r/atom (or (:backend-kind @app-state) :local))
        url*     (r/atom (or (:worker-url initial) ""))
        token*   (r/atom (or (:bearer-token initial) ""))
        branch*  (r/atom (or (:branch initial) "main"))
        msg*     (r/atom nil)]
    (fn []
      (let [sync-state (:sync-state @app-state)
            status     (or (:status sync-state) :idle)
            saved?     (and (= @kind* (:backend-kind @app-state))
                            (= {:worker-url   @url*
                                :bearer-token @token*
                                :branch       @branch*}
                               (:sync-config @app-state)))
            save!      (fn []
                         (storage/save-backend-kind! @kind*)
                         (storage/save-sync-config! {:worker-url   @url*
                                                     :bearer-token @token*
                                                     :branch       @branch*})
                         (swap! app-state assoc
                                :backend-kind @kind*
                                :sync-config  {:worker-url   @url*
                                               :bearer-token @token*
                                               :branch       @branch*})
                         (state/bind-sync-triggers!)
                         (reset! msg* {:kind :ok :text "Saved."}))]
        [:div.settings-section
         [:h4 "Sync"]
         [:div.settings-row
          [:div.lbl "Backend"
           [:div.hint "Local keeps everything in this browser. Git syncs to a GitHub repo via a Cloudflare Worker (see worker/ in the repo)."]]
          [:div
           [:label {:style {:margin-right "10px"}}
            [:input {:type "radio"
                     :checked (= :local @kind*)
                     :on-change #(reset! kind* :local)}]
            " Local"]
           [:label
            [:input {:type "radio"
                     :checked (= :git @kind*)
                     :on-change #(reset! kind* :git)}]
            " Git"]]]
         (when (= :git @kind*)
           [:<>
            [:div.settings-row
             [:div.lbl "Worker URL"
              [:div.hint "e.g. https://golova-sync.your-name.workers.dev"]]
             [:input {:value @url*
                      :on-change #(reset! url* (.. % -target -value))
                      :placeholder "https://…workers.dev"
                      :style {:min-width "320px"}}]]
            [:div.settings-row
             [:div.lbl "Bearer token"
              [:div.hint "The shared secret you set via `wrangler secret put BEARER_TOKEN`. Stored in this browser only; never committed."]]
             [:input {:type "password"
                      :value @token*
                      :on-change #(reset! token* (.. % -target -value))
                      :placeholder "…"
                      :style {:min-width "320px"}}]]
            [:div.settings-row
             [:div.lbl "Branch"
              [:div.hint "Usually 'main'."]]
             [:input {:value @branch*
                      :on-change #(reset! branch* (.. % -target -value))
                      :placeholder "main"
                      :style {:min-width "120px"}}]]])
         [:div.settings-row
          [:div.lbl "Status"
           [:div.hint (case status
                        :idle    "Idle."
                        :pulling "Pulling from remote…"
                        :pushing "Pushing to remote…"
                        :error   (str "Error: " (:error-msg sync-state))
                        (str status))]]
          [:div
           [:button {:on-click save!} (if saved? "Saved" "Save")]
           (when (= :git @kind*)
             [:button.primary
              {:disabled (or (str/blank? @url*) (str/blank? @token*)
                             (contains? #{:pulling :pushing} status))
               :style {:margin-left "8px"}
               :on-click (fn []
                           (when-not saved? (save!))
                           (-> (state/sync!)
                               (.then  (fn [_] (reset! msg* {:kind :ok :text "Sync OK."})))
                               (.catch (fn [e]
                                         (reset! msg* {:kind :err
                                                       :text (or (.-message e) (str e))})))))}
              "Sync now"])]]
         (when @msg*
           [:div.modal-sub
            {:style {:color (case (:kind @msg*) :err "var(--bad)" "var(--good)")}}
            (:text @msg*)])]))))

;; ---------------------------------------------------------------------------
;; Top-level modal dispatch

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
          (let [parent (:parent m)
                parent-label (some-> parent state/domain-info :label)]
            [:<>
             [:h3 (if parent "New subdomain" "New domain")]
             [:div.modal-sub
              (if parent
                [:span "A subdomain nested under " [:b parent-label] "."]
                "A self-contained group of facts, rules, and saved queries.")]
             [text-field {:label "Name" :id "domain-label" :placeholder "e.g. family"}]
             [:div.modal-actions
              [:button {:on-click state/close-modal!} "Cancel"]
              [:button.primary
               {:on-click (fn []
                            (let [lbl (read-field "domain-label")]
                              (when (seq lbl) (state/create-domain! lbl parent))
                              (state/close-modal!)))} "Create"]]])

          :csv-import
          [csv-import-form {:data (:data m) :filename (:filename m)}]

          :change-pred-type
          [change-pred-type-form {:pred-name (:pred-name m)
                                  :pred-arity (:pred-arity m)}]

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
                                  dom-id (:domain m)
                                  current (mapv :clause (state/rules-in dom-id))
                                  new-rules (conj current parsed)]
                              (when parsed
                                (state/set-rules! dom-id new-rules)
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
                        :default (some-> (state/domain-info (:domain m)) :label)}]
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
           [sync-settings-section]
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
                                  fname (str "golova-"
                                             (.toISOString (js/Date.))
                                             ".edn")]
                              (storage/download-blob! fname text)))}
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
            [:div.settings-row
             [:div.lbl "CSV"
              [:div.hint "Import a CSV (e.g. a Notion DB export). Each row becomes an entity; each non-empty cell becomes a triple."]]
             [:div
              [:button
               {:on-click (fn []
                            (let [inp (.createElement js/document "input")]
                              (set! (.-type inp) "file")
                              (set! (.-accept inp) ".csv,text/csv")
                              (set! (.-onchange inp)
                                    (fn [e]
                                      (when-let [f (-> e .-target .-files (aget 0))]
                                        (let [rdr (js/FileReader.)]
                                          (set! (.-onload rdr)
                                                (fn [ev]
                                                  (try
                                                    (let [txt (.. ev -target -result)
                                                          parsed (csv/parse txt)]
                                                      (if (and (seq (:headers parsed))
                                                               (seq (:rows parsed)))
                                                        (state/open-modal!
                                                          {:kind :csv-import
                                                           :data parsed
                                                           :filename (.-name f)})
                                                        (js/alert "CSV looked empty.")))
                                                    (catch :default ex
                                                      (js/alert (str "Couldn't parse CSV: "
                                                                     (.-message ex)))))))
                                          (.readAsText rdr f)))))
                              (.click inp)))}
               "Import CSV…"]]]
            [:div.settings-row
             [:div.lbl "Conversion plan"
              [:div.hint "Apply a declarative EDN plan that converts string predicates into refs, drops empties, etc. See "
               [:code "examples/people-db-cleanup.edn"] " for the shape."]]
             [:div
              [:button
               {:on-click (fn []
                            (let [inp (.createElement js/document "input")]
                              (set! (.-type inp) "file")
                              (set! (.-accept inp) ".edn,text/plain")
                              (set! (.-onchange inp)
                                    (fn [e]
                                      (when-let [f (-> e .-target .-files (aget 0))]
                                        (let [rdr (js/FileReader.)]
                                          (set! (.-onload rdr)
                                                (fn [ev]
                                                  (try
                                                    (let [txt (.. ev -target -result)
                                                          plan (reader/read-string txt)
                                                          out (state/apply-conversion-plan! plan)]
                                                      (js/console.log "plan result:" (clj->js out))
                                                      (js/alert (str "Plan applied (" (count out)
                                                                     " ops). See console for details."))
                                                      (state/close-modal!))
                                                    (catch :default ex
                                                      (js/alert (str "Plan failed: "
                                                                     (.-message ex)))))))
                                          (.readAsText rdr f)))))
                              (.click inp)))}
               "Apply plan…"]]]
            [:div.settings-row
             [:div.lbl "Rules & queries"
              [:div.hint "Import a program file: a map "
               [:code "{:rules […] :queries […]}"]
               " that adds Datalog rules and saves queries in one shot. "
               "See " [:code "examples/people-db-program.edn"] "."]]
             [:div
              [:button
               {:on-click (fn []
                            (let [inp (.createElement js/document "input")]
                              (set! (.-type inp) "file")
                              (set! (.-accept inp) ".edn,text/plain")
                              (set! (.-onchange inp)
                                    (fn [e]
                                      (when-let [f (-> e .-target .-files (aget 0))]
                                        (let [rdr (js/FileReader.)]
                                          (set! (.-onload rdr)
                                                (fn [ev]
                                                  (try
                                                    (let [txt (.. ev -target -result)
                                                          program (reader/read-string txt)
                                                          out (state/apply-program! program)]
                                                      (js/console.log "program result:" (clj->js out))
                                                      (js/alert (str "Added "
                                                                     (:rules-added out) " rule(s) and "
                                                                     (:queries-added out) " query(ies)."))
                                                      (state/close-modal!))
                                                    (catch :default ex
                                                      (js/alert (str "Program import failed: "
                                                                     (.-message ex)))))))
                                          (.readAsText rdr f)))))
                              (.click inp)))}
               "Import rules & queries…"]]]
            [:div.settings-row.danger
             [:div.lbl "Reset all data"
              [:div.hint "Wipes every domain, event, and saved query. Leaves Golova empty so you can start from scratch. This can't be undone."]]
             [:div
              [:button.ghost.danger
               {:on-click (fn []
                            (when (js/confirm "Wipe all domains and start empty? This can't be undone.")
                              (state/reset-all!)
                              (state/close-modal!)))}
               "Reset"]]]]
           [:div.modal-actions
            [:button {:on-click state/close-modal!} "Close"]]]

          nil)]])))
