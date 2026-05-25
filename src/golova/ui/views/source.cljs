(ns golova.ui.views.source
  "External-source detail page: shows the declaration, last-fetch status,
  and a Refresh button. v1 has no inline editor — sources are hand-edited
  via the file (UI form is a follow-up)."
  (:require [clojure.string :as str]
            [cljs.pprint :as pprint]
            [golova.state :as state :refer [app-state]]
            [golova.ui.popover :refer [move-to-pill]]))

(defn- pretty-edn [form]
  (str/trim-newline
    (with-out-str
      (binding [pprint/*print-pprint-dispatch* pprint/code-dispatch]
        (pprint/pprint form)))))

(defn- ago [t]
  (when t
    (let [s (long (/ (- (.now js/Date) t) 1000))]
      (cond
        (< s 5)     "just now"
        (< s 60)    (str s "s ago")
        (< s 3600)  (str (long (/ s 60)) "m ago")
        (< s 86400) (str (long (/ s 3600)) "h ago")
        :else       (str (long (/ s 86400)) "d ago")))))

(defn source-view [name]
  (let [src (first (filter #(= name (:name %))
                           (get-in @app-state [:schema :sources])))]
    (if-not src
      [:div.view [:div.empty [:h3 "Source not found"]]]
      (let [domain-id (:domain src)
            sstate    (get-in @app-state [:sync-state :sources name])
            status    (or (:status sstate) :idle)
            err       (:error sstate)
            busy?     (= :refreshing status)
            refresh!  (fn []
                        (-> (state/refresh-source! name)
                            (.catch (fn [_] nil))))]
        [:div.view
         [:div.view-head
          [:h2.mono name]
          [:span.pill (clojure.core/name (:kind src))]
          [move-to-pill domain-id
           (fn [_src _dst]
             (js/alert "Moving sources across domains isn't implemented yet — edit sources.edn directly."))]
          [:div.actions-right
           [:button.primary
            {:disabled busy?
             :on-click refresh!}
            (case status
              :refreshing "Refreshing…"
              "Refresh")]
           [:button.ghost.danger
            {:on-click (fn []
                         (when (js/confirm (str "Delete source declaration " name
                                                "?\nFacts already imported stay in events.edn."))
                           (state/delete-source! domain-id name)
                           (state/select! {:kind :rules :domain domain-id})))}
            "Delete"]]]

         [:div.settings-section
          [:h4 "Status"]
          [:div.settings-row
           [:div.lbl "Last fetched"
            [:div.hint
             (if-let [t (:last-fetched-at sstate)]
               (str (ago t)
                    " • " (or (:last-record-count sstate) 0) " records"
                    " → " (or (:last-triple-count sstate) 0) " triples")
               "Never (this session).")]]]
          (when (= :error status)
            [:div.settings-hint.err
             "Error: " err])]

         [:div.settings-section
          [:h4 "Declaration"]
          [:pre.mono.code-block
           {:style {:padding "12px"
                    :background "var(--panel)"
                    :border "1px solid var(--border)"
                    :border-radius "6px"
                    :overflow-x "auto"
                    :font-size "12px"
                    :line-height "1.5"}}
           (pretty-edn (dissoc src :domain))]
          [:div.settings-hint
           "Edit via " [:code "domains/" (clojure.core/name domain-id)
                        "/sources.edn"] " in your kb repo and Sync, or via the file in localStorage."]]]))))
