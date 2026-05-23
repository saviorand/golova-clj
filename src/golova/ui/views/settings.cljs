(ns golova.ui.views.settings
  "Settings page — full-view replacement for the settings modal.
  Covers sync backend config, data export/import, and destructive reset."
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [golova.state :as state :refer [app-state]]
            [golova.storage :as storage]
            [golova.csv :as csv]))

;; ---------------------------------------------------------------------------
;; Sync status helpers

(defn- short-sha [sha]
  (when sha (subs (str sha) 0 (min 8 (count (str sha))))))

(defn- time-ago [t]
  (when t
    (let [s (long (/ (- (.now js/Date) t) 1000))]
      (cond
        (< s 5)     "just now"
        (< s 60)    (str s "s ago")
        (< s 3600)  (str (long (/ s 60)) "m ago")
        (< s 86400) (str (long (/ s 3600)) "h ago")
        :else       (str (long (/ s 86400)) "d ago")))))

;; ---------------------------------------------------------------------------
;; Sync section (Form-2: local state for the config fields)

(defn- sync-section []
  (let [initial (or (:sync-config @app-state) {})
        kind*   (r/atom (or (:backend-kind @app-state) :local))
        url*    (r/atom (or (:worker-url initial) ""))
        token*  (r/atom (or (:bearer-token initial) ""))
        branch* (r/atom (or (:branch initial) "main"))
        msg*    (r/atom nil)]
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
           [:div.hint "Local keeps everything in this browser. Git syncs to a GitHub repo via a Cloudflare Worker."]]
          [:div.settings-controls
           [:label.radio-label
            [:input {:type "radio"
                     :checked (= :local @kind*)
                     :on-change #(reset! kind* :local)}]
            " Local"]
           [:label.radio-label
            [:input {:type "radio"
                     :checked (= :git @kind*)
                     :on-change #(reset! kind* :git)}]
            " Git"]]]

         (when (= :git @kind*)
           [:<>
            [:div.settings-row
             [:div.lbl "Worker URL"
              [:div.hint "e.g. https://golova-sync.your-name.workers.dev"]]
             [:input.settings-input
              {:value @url*
               :on-change #(reset! url* (.. % -target -value))
               :placeholder "https://…workers.dev"}]]
            [:div.settings-row
             [:div.lbl "Bearer token"
              [:div.hint "Shared secret set via `wrangler secret put BEARER_TOKEN`. Stored in this browser only."]]
             [:input.settings-input
              {:type "password"
               :value @token*
               :on-change #(reset! token* (.. % -target -value))
               :placeholder "…"}]]
            [:div.settings-row
             [:div.lbl "Branch"
              [:div.hint "Usually 'main'."]]
             [:input.settings-input
              {:value @branch*
               :on-change #(reset! branch* (.. % -target -value))
               :placeholder "main"}]]])

         (when (= :git @kind*)
           (let [pending      (state/pending-changes)
                 pending-n    (or (:count pending) 0)
                 busy?        (contains? #{:pulling :pushing} status)
                 missing-cfg? (or (str/blank? @url*) (str/blank? @token*))
                 run!         (fn [op success-msg]
                                (when-not saved? (save!))
                                (-> (op)
                                    (.then  #(reset! msg* {:kind :ok :text success-msg}))
                                    (.catch #(reset! msg* {:kind :err
                                                           :text (or (.-message %) (str %))}))))
                 pull-confirm-and-run!
                 (fn []
                   (when (or (zero? pending-n)
                             (js/confirm
                               (str "Pull will overwrite "
                                    pending-n " unpushed local change"
                                    (when (not= 1 pending-n) "s")
                                    " on this device. Continue?")))
                     (run! state/sync-pull! "Pulled.")))]
             [:<>
              [:div.settings-row
               [:div.lbl "Last pull"
                [:div.hint
                 (if-let [t (:last-pulled-at sync-state)]
                   (str (time-ago t) " • " (short-sha (:last-pulled-head sync-state)))
                   "Never (this session).")]]
               [:div.settings-controls
                [:button {:disabled (or busy? missing-cfg?)
                          :on-click pull-confirm-and-run!}
                 "Pull ↓"]]]

              [:div.settings-row
               [:div.lbl "Last push"
                [:div.hint
                 (if-let [t (:last-pushed-at sync-state)]
                   (str (time-ago t) " • " (short-sha (:last-pushed-head sync-state)))
                   "Never (this session).")]]
               [:div.settings-controls
                [:button {:disabled (or busy? missing-cfg?
                                        (nil? (:last-pulled-head sync-state))
                                        (zero? pending-n))
                          :on-click #(run! state/sync-push! "Pushed.")}
                 "Push ↑"]]]

              [:div.settings-row
               [:div.lbl "Local changes"
                [:div.hint
                 (case (:status pending)
                   :unknown "Pull first to see what's pending."
                   :synced  "Up to date with last pull."
                   :pending (str pending-n " file"
                                 (when (not= 1 pending-n) "s")
                                 " pending push."))]]
               [:div.settings-controls
                [:button.primary
                 {:disabled (or busy? missing-cfg?)
                  :title "Pull, then push if anything changed."
                  :on-click (fn []
                              (when (or (zero? pending-n)
                                        (js/confirm
                                          (str "Sync will pull from remote (overwriting "
                                               pending-n " unpushed local change"
                                               (when (not= 1 pending-n) "s")
                                               ") and then push. Continue?")))
                                (run! state/sync! "Sync OK.")))}
                 "Sync ↕"]]]]))

         [:div.settings-row
          [:div.lbl "Status"
           [:div.hint (case status
                        :idle    "Idle."
                        :pulling "Pulling from remote…"
                        :pushing "Pushing to remote…"
                        :error   (str "Error: " (:error-msg sync-state))
                        (str status))]]
          [:div.settings-controls
           [:button {:on-click save!} (if saved? "Saved" "Save")]]]

         (when @msg*
           [:div.settings-hint
            {:class (when (= :err (:kind @msg*)) "err")}
            (:text @msg*)])]))))

;; ---------------------------------------------------------------------------
;; Data section

(defn- data-section []
  [:div.settings-section
   [:h4 "Data"]

   [:div.settings-row
    [:div.lbl "Snapshot"
     [:div.hint "EDN export of every domain. Import replaces current state."]]
    [:div.settings-controls
     [:button
      {:on-click (fn []
                   (let [snap (state/serializable @app-state)
                         text (storage/snapshot->json snap)
                         fname (str "golova-" (.toISOString (js/Date.)) ".edn")]
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
                                             (state/import-snapshot! snap))
                                           (catch :default ex
                                             (js/alert (str "Bad snapshot: " (.-message ex)))))))
                                 (.readAsText rdr f)))))
                     (.click inp)))}
      "Import…"]]]

   [:div.settings-row
    [:div.lbl "CSV"
     [:div.hint "Import a CSV (e.g. a Notion DB export). Each row becomes an entity."]]
    [:div.settings-controls
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
                                             (js/alert (str "Couldn't parse CSV: " (.-message ex)))))))
                                 (.readAsText rdr f)))))
                     (.click inp)))}
      "Import CSV…"]]]

   [:div.settings-row
    [:div.lbl "Rules & queries"
     [:div.hint "Import a program file: " [:code "{:rules […] :queries […]}"] " adds rules and saved queries in one shot."]]
    [:div.settings-controls
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
                                             (js/alert (str "Added " (:rules-added out)
                                                            " rule(s) and " (:queries-added out)
                                                            " query(ies).")))
                                           (catch :default ex
                                             (js/alert (str "Program import failed: " (.-message ex)))))))
                                 (.readAsText rdr f)))))
                     (.click inp)))}
      "Import rules & queries…"]]]

   [:div.settings-row.danger
    [:div.lbl "Reset all data"
     [:div.hint "Wipes every domain, event, and saved query. This can't be undone."]]
    [:div.settings-controls
     [:button.ghost.danger
      {:on-click (fn []
                   (when (js/confirm "Wipe all domains and start empty? This can't be undone.")
                     (state/reset-all!)))}
      "Reset"]]]])

;; ---------------------------------------------------------------------------
;; Top-level settings view

(defn settings-view []
  [:div.view
   [:div.view-head
    [:h2 "Settings"]]
   [sync-section]
   [data-section]])
