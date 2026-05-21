(ns golova.state.ui
  "UI-only state mutations: selection, theme, sidebar expansion, modal,
  popover, and command palette. None of these touch :db; they only flip
  flags and persist."
  (:require [golova.state.core :as core :refer [app-state]]))

;; ---------------------------------------------------------------------------
;; Selection / navigation

(defn select! [sel]
  (swap! app-state assoc :selection sel)
  (core/save!))

(defn go-home! []
  (swap! app-state assoc :selection {:kind :home})
  (core/save!))

(defn switch-domain! [id]
  (swap! app-state assoc
         :current-domain id
         :selection {:kind :rules :domain id}
         :error nil)
  (core/save!))

;; ---------------------------------------------------------------------------
;; Sidebar expansion

(defn toggle-domain! [id]
  (swap! app-state update :expanded
         (fn [s] (let [s (or s #{})]
                   (if (contains? s id) (disj s id) (conj s id)))))
  (core/save!))

(defn expand-domain! [id]
  (swap! app-state update :expanded (fnil conj #{}) id)
  (core/save!))

(defn toggle-subsection! [domain-id sub-key]
  (let [k [domain-id sub-key]]
    (swap! app-state update :expanded-subs
           (fn [s] (let [s (or s #{})]
                     (if (contains? s k) (disj s k) (conj s k))))))
  (core/save!))

(defn expand-subsection! [domain-id sub-key]
  (swap! app-state update :expanded-subs (fnil conj #{}) [domain-id sub-key])
  (core/save!))

;; ---------------------------------------------------------------------------
;; Theme / home

(defn set-theme! [t]
  (swap! app-state assoc :theme t)
  (core/save!))

(defn toggle-onboarding! []
  (swap! app-state update-in [:home :onboarding-collapsed?] not)
  (core/save!))

;; ---------------------------------------------------------------------------
;; Modal

(defn open-modal! [m] (swap! app-state assoc :modal m))
(defn close-modal! [] (swap! app-state assoc :modal nil))

;; ---------------------------------------------------------------------------
;; Command palette

(defn open-palette! [] (swap! app-state assoc :palette {:open? true :query "" :index 0}))
(defn close-palette! [] (swap! app-state assoc :palette nil))
(defn toggle-palette! []
  (if (get-in @app-state [:palette :open?]) (close-palette!) (open-palette!)))
(defn set-palette-query! [q] (swap! app-state update :palette assoc :query q :index 0))
(defn palette-move! [delta] (swap! app-state update-in [:palette :index] (fnil + 0) delta))
(defn palette-set-index! [i] (swap! app-state assoc-in [:palette :index] i))
