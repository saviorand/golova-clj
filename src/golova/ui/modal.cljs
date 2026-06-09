(ns golova.ui.modal
  "All modal dialogs using antd Modal. Each form uses antd Form, Input,
  Select, and Button components for a polished, consistent look."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            ["@ant-design/icons" :as icons]
            [golova.state :as state :refer [app-state]]
            [golova.state.inference :as inference]
            [golova.storage :as storage]
            [golova.csv :as csv]
            [golova.ui.typed :refer [chip-editor]]
            [golova.ui.antd :as antd]))

(declare sync-settings-section)

;; ---------------------------------------------------------------------------
;; Helper to read form fields by DOM id

(defn- text-field [{:keys [label id placeholder default rows]}]
  (let [el-id (str "f-" (name id))]
    [:> (.-FormItem antd/antd)
     {:label label}
     (if rows
       [:> (.-InputTextArea antd/antd)
        {:id el-id :rows rows :defaultValue (or default "")
             :placeholder placeholder :spellCheck "false"}]
       [:> (.-Input antd/antd)
        {:id el-id :defaultValue (or default "") :placeholder placeholder}])]))

(defn- read-field [id]
  (some-> (.getElementById js/document (str "f-" (name id))) .-value str/trim))

;; ---------------------------------------------------------------------------
;; New predicate form

(defn- arg-type-options [_domain-id]
  (let [declared (->> (get-in @app-state [:schema :types])
                      (map :name)
                      sort)]
    (vec (concat ["atom" "int" "string"] declared))))

(defn new-predicate-form [{:keys [domain preset-name]}]
  (let [pname (r/atom (or preset-name ""))
        args  (r/atom ["atom" "atom"])]
    (fn [{:keys [domain]}]
      (let [opts (arg-type-options domain)
            valid? (and (seq (str/trim @pname)) (seq @args))]
        [:> (.-Form antd/antd)
         {:layout "vertical"}
         [:> (.-TypographyParagraph antd/antd)
          {:type "secondary"}
          "A typed relation. Each arg picks its type from built-ins or any declared type."]
         [:> (.-FormItem antd/antd)
          {:label "Name" :required true}
          [:> (.-Input antd/antd)
           {:placeholder "e.g. age"
                :value @pname
                :autoFocus true
                :onChange #(reset! pname (.. % -target -value))}]]
         [:> (.-FormItem antd/antd)
          {:label "Arg types"}
          [:div.arg-rows
           (for [[i t] (map-indexed vector @args)]
             ^{:key i}
             [:div.arg-row
              [:span.arg-idx (str "arg " (inc i))]
              [:> (.-Select antd/antd)
               {:value t
                    :style #js {:flex 1}
                    :onChange #(swap! args assoc i %)
                    :options (clj->js (map (fn [o] {:value o :label o}) opts)
                                      :keyword-fn name)}]
              (when (> (count @args) 1)
                [:> (.-Button antd/antd)
                 {:type "text" :danger true :size "small"
                      :onClick #(swap! args (fn [xs]
                                              (vec (concat (subvec xs 0 i)
                                                           (subvec xs (inc i))))))}
                 "×"])])
           [:> (.-Button antd/antd)
            {:type "dashed" :size "small"
                 :onClick #(swap! args conj "atom")
                 :style #js {:marginTop 4}}
            "+ Add arg"]]]
         [:div.modal-actions
          [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
          [:> (.-Button antd/antd)
           {:type "primary" :disabled (not valid?)
                :onClick (fn []
                           (let [n (str/trim @pname)]
                             (when valid?
                               (state/declare-predicate! domain n @args)
                               (state/select! {:kind :predicate :name n
                                               :arity (count @args)}))
                             (state/close-modal!)))}
           "Declare"]]]))))

;; ---------------------------------------------------------------------------
;; New type form

(defn new-type-form [domain-id]
  (let [type-name (r/atom "")
        ctors (r/atom [])]
    (fn [domain-id]
      [:> (.-Form antd/antd)
       {:layout "vertical"}
       [:> (.-TypographyParagraph antd/antd)
        {:type "secondary"}
        "A named enum of keyword values, e.g. "
        [:code "person"] " = " [:code "alice | bob | carol"]
        ". When used as a predicate arg type, the predicate stores "
        [:code ":db/type/ref"] " references."]
       [:> (.-FormItem antd/antd)
        {:label "Name" :required true}
        [:> (.-Input antd/antd)
         {:placeholder "e.g. person"
              :value @type-name
              :autoFocus true
              :onChange #(reset! type-name (.. % -target -value))}]]
       [:> (.-FormItem antd/antd)
        {:label "Constructors"}
        [chip-editor
         {:state-atom ctors
          :placeholder "type a name, Enter to add"
          :suggestions-fn #(state/untyped-atoms domain-id)}]]
       [:div.modal-actions
        [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
        [:> (.-Button antd/antd)
         {:type "primary"
              :disabled (or (str/blank? @type-name) (empty? @ctors))
              :onClick (fn []
                         (let [n (str/trim @type-name)]
                           (when (seq n)
                             (state/declare-type! domain-id n @ctors)
                             (state/select! {:kind :type :name n}))
                           (state/close-modal!)))}
         "Create type"]]])))

;; ---------------------------------------------------------------------------
;; CSV import form

(defn- default-id-col [{:keys [headers]}]
  (when (seq headers) 0))

(defn csv-import-form [{:keys [data filename]}]
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
        [:> (.-Form antd/antd)
         {:layout "vertical"}
         [:> (.-TypographyParagraph antd/antd)
          {:type "secondary"}
          (if filename [:span [:code filename] " — "])
          (:row-count preview) " rows · " (:col-count preview) " columns"]
         [:> (.-FormItem antd/antd)
          {:label "Target domain"}
          [:> (.-Select antd/antd)
           {:value (if create-new? "__new__" (name @dom*))
                :onChange #(reset! dom* (if (= "__new__" %) :__new__ (keyword %)))
                :options (clj->js
                          (concat
                           (for [{:keys [id label]} domains]
                             {:value (name id) :label label})
                           [{:value "__new__"
                             :label (str "+ New domain: " new-label)}])
                          :keyword-fn name)}]
          (when create-new?
            [:> (.-Input antd/antd)
             {:placeholder "domain name"
                  :value @new-dom
                  :onChange #(reset! new-dom (.. % -target -value))
                  :style #js {:marginTop 6}}])]
         [:> (.-FormItem antd/antd)
          {:label "Identity column"}
          [:> (.-Select antd/antd)
           {:value (str id-idx)
                :onChange #(reset! id-idx* (js/parseInt % 10))
                :options (clj->js
                          (for [[i c] (map-indexed vector (:columns preview))]
                            {:value (str i)
                             :label (str (:name c) "  (" (:non-empty c) " non-empty, "
                                         (count (distinct (map #(str/trim (nth % i ""))
                                                               (:rows data)))) " distinct)")})
                          :keyword-fn name)}]
          [:> (.-TypographyText antd/antd)
           {:type "secondary" :style #js {:marginTop 4 :display "block"}}
           "Each row's " [:code (:name id-col)] " becomes an atom keyword."]]
         (when (pos? n-empty-cols)
           [:> (.-Checkbox antd/antd)
            {:checked @skip-empty?*
                 :onChange #(reset! skip-empty?* (.. % -target -checked))
                 :style #js {:marginBottom 12}}
            (str "Skip the " n-empty-cols " column"
                 (when (not= 1 n-empty-cols) "s") " with no values")])
         [:> (.-Alert antd/antd)
          {:type "info" :showIcon true
               :message (r/as-element
                         [:span
                          "Will create " [:b n-preds] " predicate"
                          (when (not= 1 n-preds) "s") ", "
                          [:b n-entities] " entit"
                          (if (= 1 n-entities) "y" "ies") ", and "
                          [:b n-triples] " triple"
                          (when (not= 1 n-triples) "s") "."
                          (when (pos? n-skipped)
                            [:span " " [:b n-skipped] " row"
                             (when (not= 1 n-skipped) "s") " skipped."])])
               :style #js {:marginBottom 16}}]
         (when @msg
           [:> (.-Alert antd/antd)
            {:type (if (= :err (:kind @msg)) "error" "success")
                 :message (:text @msg)
                 :style #js {:marginBottom 12}}])
         [:div.modal-actions
          [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
          [:> (.-Button antd/antd)
           {:type "primary" :disabled (not valid?)
                :onClick (fn []
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
                             (state/close-modal!)))}
           "Import"]]]))))

;; ---------------------------------------------------------------------------
;; Change-predicate-type form

(defn change-pred-type-form [{:keys [pred-name pred-arity]}]
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
        [:> (.-Form antd/antd)
         {:layout "vertical"}
         [:> (.-TypographyParagraph antd/antd)
          {:type "secondary"}
          "Convert " [:code (str ":" pred-name)] "'s values into refs. "
          [:b unique-values] " unique value"
          (when (not= 1 unique-values) "s") " will become atoms."]
         [:> (.-FormItem antd/antd)
          {:label "Target domain"}
          [:> (.-Select antd/antd)
           {:value (if new? "__new__" (name t))
                :onChange #(reset! target* (if (= "__new__" %)
                                             :__new__ (keyword %)))
                :options (clj->js
                          (concat
                           (for [{:keys [id label]} domains]
                             {:value (name id) :label label})
                           [{:value "__new__"
                             :label (str "+ New domain: " suggested)}])
                          :keyword-fn name)}]
          (when new?
            [:> (.-Input antd/antd)
             {:placeholder "domain name"
                  :value @new-dom
                  :onChange #(reset! new-dom (.. % -target -value))
                  :style #js {:marginTop 6}}])]
         [:> (.-Checkbox antd/antd)
          {:checked @make-enum?*
               :onChange #(reset! make-enum?* (.. % -target -checked))
               :style #js {:marginBottom 8}}
          "Also declare an enum type grouping the new atoms"]
         (when @make-enum?*
           [:> (.-Input antd/antd)
            {:placeholder "enum type name (e.g. organization)"
                 :value @enum-name
                 :onChange #(reset! enum-name (.. % -target -value))
                 :style #js {:marginBottom 12}}])
         [:div.modal-actions
          [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
          [:> (.-Button antd/antd)
           {:type "primary" :disabled (not valid?)
                :onClick (fn []
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
                             (state/close-modal!)))}
           "Convert"]]]))))

;; ---------------------------------------------------------------------------
;; Sync settings section

(defn sync-settings-section []
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
        [:div
         [:> (.-Divider antd/antd)]
         [:> (.-TypographyTitle antd/antd) {:level 5} "Sync"]
         [:> (.-Form antd/antd)
          {:layout "vertical" :size "small"}
          [:> (.-FormItem antd/antd)
           {:label "Backend"}
           [:> (.-RadioGroup antd/antd)
            {:value @kind*
                 :onChange #(reset! kind* (keyword %))
                 :options #js [#js {:value "local" :label "Local"}
                               #js {:value "git" :label "Git"}]}]
           [:> (.-TypographyText antd/antd)
            {:type "secondary" :style #js {:display "block" :marginTop 4}}
            "Local keeps everything in this browser. Git syncs to GitHub via a Cloudflare Worker."]]
          (when (= :git @kind*)
            [:<>
             [:> (.-FormItem antd/antd)
              {:label "Worker URL"}
              [:> (.-Input antd/antd)
               {:value @url*
                    :onChange #(reset! url* (.. % -target -value))
                    :placeholder "https://…workers.dev"}]]
             [:> (.-FormItem antd/antd)
              {:label "Bearer token"}
              [:> (.-Input antd/antd)
               {:type "password" :value @token*
                    :onChange #(reset! token* (.. % -target -value))
                    :placeholder "…"}]]
             [:> (.-FormItem antd/antd)
              {:label "Branch"}
              [:> (.-Input antd/antd)
               {:value @branch*
                    :onChange #(reset! branch* (.. % -target -value))
                    :placeholder "main"}]]])
          [:div.modal-actions
           [:> (.-Button antd/antd)
            {:onClick save!} (if saved? "Saved" "Save")]
           (when (= :git @kind*)
             [:> (.-Button antd/antd)
              {:type "primary"
                   :disabled (or (str/blank? @url*) (str/blank? @token*)
                                 (contains? #{:pulling :pushing} status))
                   :onClick (fn []
                              (when-not saved? (save!))
                              (-> (state/sync!)
                                  (.then  (fn [_] (reset! msg* {:kind :ok :text "Sync OK."})))
                                  (.catch (fn [e]
                                            (reset! msg* {:kind :err
                                                          :text (or (.-message e) (str e))})))))}
              "Sync now"])]]
         (when @msg*
           [:> (.-Alert antd/antd)
            {:type (if (= :err (:kind @msg*)) "error" "success")
                 :message (:text @msg*)
                 :style #js {:marginTop 12}}])]))))

;; ---------------------------------------------------------------------------
;; Top-level modal dispatch

(defn modal []
  (let [m (:modal @app-state)]
    (when m
      (let [modal-props
            (fn [title & [width]]
              {:open true
               :title title
               :width (or width 520)
               :onCancel state/close-modal!
               :footer nil
               :destroyOnClose true})]
        (case (:kind m)
          :new-domain
          (let [parent (:parent m)
                parent-label (some-> parent state/domain-info :label)]
            [:> (.-Modal antd/antd)
             (modal-props (if parent "New Subdomain" "New Domain"))
             [:> (.-Form antd/antd)
              {:layout "vertical"}
              [:> (.-TypographyParagraph antd/antd)
               {:type "secondary"}
               (if parent
                 [:span "A subdomain nested under " [:b parent-label] "."]
                 "A self-contained group of facts, rules, and saved queries.")]
              [text-field {:label "Name" :id "domain-label" :placeholder "e.g. family"}]
              [:div.modal-actions
               [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
               [:> (.-Button antd/antd)
                {:type "primary"
                     :onClick (fn []
                                (let [lbl (read-field "domain-label")]
                                  (when (seq lbl) (state/create-domain! lbl parent))
                                  (state/close-modal!)))}
                "Create"]]]])

          :csv-import
          [:> (.-Modal antd/antd)
           (modal-props "Import CSV" 580)
           [csv-import-form {:data (:data m) :filename (:filename m)}]]

          :change-pred-type
          [:> (.-Modal antd/antd)
           (modal-props "Change Type" 520)
           [change-pred-type-form {:pred-name (:pred-name m)
                                    :pred-arity (:pred-arity m)}]]

          :new-type
          [:> (.-Modal antd/antd)
           (modal-props "New Type")
           [new-type-form (:domain m)]]

          :new-predicate
          [:> (.-Modal antd/antd)
           (modal-props "New Predicate")
           [new-predicate-form {:domain (:domain m)
                                :preset-name (:preset-name m)}]]

          :new-rule
          [:> (.-Modal antd/antd)
           (modal-props "New Rule")
           [:> (.-Form antd/antd)
            {:layout "vertical"}
            [:> (.-TypographyParagraph antd/antd)
             {:type "secondary"}
             "Datalog rule. Format: " [:code "[(head ?a ?b) body…]"]]
            [text-field {:label "Rule (EDN)" :id "rule-text"
                         :placeholder "[(ancestor ?a ?d) [?a :parent ?d]]"
                         :rows 4}]
            [:div.modal-actions
             [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
             [:> (.-Button antd/antd)
              {:type "primary"
                   :onClick (fn []
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
              "Append rule"]]]]

          :new-query
          [:> (.-Modal antd/antd)
           (modal-props "New Saved Query")
           [:> (.-Form antd/antd)
            {:layout "vertical"}
            [text-field {:label "Name" :id "q-name" :placeholder "e.g. all-adults"}]
            [text-field {:label "Query body" :id "q-text"
                         :placeholder "e.g. parent(?p, ?c)"}]
            [:div.modal-actions
             [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
             [:> (.-Button antd/antd)
              {:type "primary"
                   :onClick (fn []
                              (let [name (read-field "q-name")
                                    text (read-field "q-text")]
                                (when (seq name)
                                  (state/save-query! (:domain m) name text)
                                  (state/select! {:kind :query :name name}))
                                (state/close-modal!)))}
              "Save"]]]]

          :rename-domain
          [:> (.-Modal antd/antd)
           (modal-props "Rename Domain")
           [:> (.-Form antd/antd)
            {:layout "vertical"}
            [text-field {:label "Name" :id "rename-domain-label"
                         :default (some-> (state/domain-info (:domain m)) :label)}]
            [:div.modal-actions
             [:> (.-Button antd/antd) {:onClick state/close-modal!} "Cancel"]
             [:> (.-Button antd/antd)
              {:type "primary"
                   :onClick (fn []
                              (let [lbl (read-field "rename-domain-label")]
                                (when (seq lbl)
                                  (state/rename-domain! (:domain m) lbl))
                                (state/close-modal!)))}
              "Rename"]]]]

          :inference
          (let [m-data   m
                mode*    (r/atom :deduction)
                query*   (r/atom "")
                pred*    (r/atom "")
                vars*    (r/atom "X")
                abds*    (r/atom "")
                running* (r/atom false)
                result*  (r/atom nil)]
            [:> (.-Modal antd/antd)
             (merge (modal-props "Run Inference" 560)
                    {:onCancel (fn [] (reset! result* nil) (state/close-modal!))})
             [:> (.-Form antd/antd)
              {:layout "vertical"}
              [:> (.-TypographyParagraph antd/antd)
               {:type "secondary"}
               "Runs scasp-clj over the current domain's facts and rules. "
               "Results are stored as derived triples."]
              [:> (.-FormItem antd/antd) {:label "Mode"}
               [:> (.-RadioGroup antd/antd)
                {:value @mode*
                 :onChange #(reset! mode* (keyword (.. % -target -value)))
                 :options #js [#js {:value "deduction" :label "Deduction"}
                               #js {:value "abduction" :label "Abduction"}]}]]
              [:> (.-FormItem antd/antd)
               {:label "Query (scasp goal EDN)"
                :extra "e.g. {:op :can-fly :args [\"X\"]}  or a vector of goals"}
               [:> (.-InputTextArea antd/antd)
                {:rows 3
                 :value @query*
                 :spellCheck "false"
                 :placeholder "{:op :can-fly :args [\"X\"]}"
                 :onChange #(reset! query* (.. % -target -value))}]]
              [:> (.-FormItem antd/antd)
               {:label "Variable names (comma-separated)"
                :extra "Variables to extract, e.g. X  or  X,Y for arity-2 results"}
               [:> (.-Input antd/antd)
                {:value @vars*
                 :placeholder "X"
                 :onChange #(reset! vars* (.. % -target -value))}]]
              [:> (.-FormItem antd/antd)
               {:label "Store as predicate"
                :extra "Keyword for derived triples (blank = infer from query op)"}
               [:> (.-Input antd/antd)
                {:value @pred*
                 :placeholder "(inferred from query)"
                 :onChange #(reset! pred* (.. % -target -value))}]]
              (when (= :abduction @mode*)
                [:> (.-FormItem antd/antd)
                 {:label "Abducibles (comma-separated functor strings)"
                  :extra "e.g. fly/1,walk/1 — predicates the solver may hypothesise"}
                 [:> (.-Input antd/antd)
                  {:value @abds*
                   :placeholder "e.g. vuln/1,misconfigured/1"
                   :onChange #(reset! abds* (.. % -target -value))}]])
              (when @result*
                (let [{:keys [stored-count triples error]} @result*]
                  (if error
                    [:> (.-Alert antd/antd)
                     {:type "error" :showIcon true
                      :message "Inference error"
                      :description (str error)
                      :style #js {:marginBottom 12}}]
                    [:> (.-Alert antd/antd)
                     {:type "success" :showIcon true
                      :message (str stored-count " new triple"
                                    (when (not= 1 stored-count) "s") " stored"
                                    (when (pos? (count triples))
                                      (str " (" (count triples) " derived total)")))
                      :style #js {:marginBottom 12}}])))
              [:div.modal-actions
               [:> (.-Button antd/antd)
                {:onClick (fn [] (reset! result* nil) (state/close-modal!))}
                "Close"]
               [:> (.-Button antd/antd)
                {:type "primary"
                 :loading @running*
                 :disabled (str/blank? (str/trim @query*))
                 :onClick
                 (fn []
                   (reset! result* nil)
                   (reset! running* true)
                   (try
                     (let [q-edn    (reader/read-string (str/trim @query*))
                           query    (if (vector? q-edn) q-edn [q-edn])
                           vnames   (mapv str/trim (str/split @vars* #","))
                           pred     (let [p (str/trim @pred*)]
                                      (when (seq p) (keyword p)))
                           abds     (when (= :abduction @mode*)
                                      (->> (str/split @abds* #",")
                                           (mapv str/trim)
                                           (filterv seq)
                                           set))
                           opts     (cond-> {:mode       @mode*
                                             :var-names  (filterv seq vnames)}
                                      pred (assoc :predicate pred)
                                      (seq abds) (assoc :abducibles abds)
                                      (:domain m-data) (assoc :domain (:domain m-data)))
                           r        (inference/run-inference! query opts)]
                       (reset! result* r))
                     (catch :default e
                       (reset! result* {:error (or (.-message e) (str e))}))
                     (finally
                       (reset! running* false))))}
                "Run"]]]])

          :settings
          [:> (.-Modal antd/antd)
           {:open true
                :title "Settings"
                :width 640
                :onCancel state/close-modal!
                :footer (r/as-element
                         [:> (.-Button antd/antd)
                          {:onClick state/close-modal!} "Close"])
                :destroyOnClose true}
           [:> (.-TypographyParagraph antd/antd)
            {:type "secondary"}
            "Local data lives in this browser's storage."]
           [sync-settings-section]
           [:> (.-Divider antd/antd)]
           [:> (.-TypographyTitle antd/antd) {:level 5} "Data"]
           [:> (.-Descriptions antd/antd)
            {:column 1 :size "small" :bordered true
                 :style #js {:marginBottom 16}}
            [:> (.-DescriptionsItem antd/antd)
             {:label "Snapshot"}
             [:span "EDN export of every domain."]
             [:br]
             [:> (.-Space antd/antd) {:style #js {:marginTop 8}}
              [:> (.-Button antd/antd)
               {:size "small"
                    :icon (r/as-element [:> (.-ExportOutlined icons)])
                    :onClick (fn []
                               (let [snap (state/serializable @app-state)
                                     text (storage/snapshot->json snap)
                                     fname (str "golova-" (.toISOString (js/Date.)) ".edn")]
                                 (storage/download-blob! fname text)))}
               "Export"]
              [:> (.-Button antd/antd)
               {:size "small"
                    :icon (r/as-element [:> (.-ImportOutlined icons)])
                    :onClick (fn []
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
            [:> (.-DescriptionsItem antd/antd)
             {:label "CSV"}
             [:span "Import a CSV (e.g. a Notion DB export)."]
             [:br]
             [:> (.-Button antd/antd)
              {:size "small" :style #js {:marginTop 8}
                   :icon (r/as-element [:> (.-ImportOutlined icons)])
                   :onClick (fn []
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
                                                        (js/alert (str "Couldn't parse CSV: " (.-message ex)))))))
                                            (.readAsText rdr f)))))
                                (.click inp)))}
              "Import CSV…"]]]
            [:> (.-DescriptionsItem antd/antd)
             {:label "Reset"}
             [:span {:style {:color "var(--ant-color-error)"}} "Wipe all data."]
             [:br]
             [:> (.-Button antd/antd)
              {:danger true :size "small" :style #js {:marginTop 8}
                   :onClick (fn []
                              (when (js/confirm "Wipe all domains and start empty? This can't be undone.")
                                (state/reset-all!)
                                (state/close-modal!)))}
              "Reset All"]]]

          nil)))))
