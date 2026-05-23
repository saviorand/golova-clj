(ns golova.app
  "Entry point. Mounts the Reagent root, hydrates state from storage,
  applies theme to the <html> element, and rebuilds the engine."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [golova.state :as state :refer [app-state]]
            [golova.state.sync :as sync]
            [golova.storage :as storage]
            [golova.router :as router]
            [golova.ui :as ui]))

;; ---------------------------------------------------------------------------
;; Theme: reflect (:theme app-state) onto <html class>.

(defn- sync-theme! []
  (let [cl (.. js/document -documentElement -classList)]
    (if (= :dark (:theme @app-state))
      (.add cl "dark")
      (.remove cl "dark"))))

;; ---------------------------------------------------------------------------
;; Global keyboard shortcuts.

(defn- bind-shortcuts! []
  (.addEventListener
   js/document "keydown"
   (fn [e]
     (let [cmd?  (or (.-metaKey e) (.-ctrlKey e))
           key   (.-key e)]
       (cond
         (and cmd? (= (.toLowerCase key) "k"))
         (do (.preventDefault e)
             (state/toggle-palette!))

         (= key "Escape")
         (cond
           (get-in @app-state [:palette :open?]) (state/close-palette!)
           (:popover @app-state) (swap! app-state assoc :popover nil)
           (:modal   @app-state) (state/close-modal!)))))))

;; ---------------------------------------------------------------------------
;; Boot

(defonce ^:private root-atom (atom nil))

(defn render! []
  (when-let [root @root-atom]
    (rdom/render root [ui/root])))

(defn init []
  (sync-theme!)
  ;; Persist theme on change.
  (add-watch app-state :theme-sync (fn [_ _ old new] (when (not= (:theme old) (:theme new)) (sync-theme!))))

  ;; Hydrate from localStorage backend.
  (let [backend (storage/local)]
    (swap! app-state assoc
           :device-id     (storage/ensure-device-id!)
           :backend-kind  (storage/load-backend-kind)
           :sync-config   (storage/load-sync-config))
    (state/load-or-seed! backend))
  (state/rebuild!)

  ;; URL ↔ :selection two-way sync.
  (router/install!)

  ;; Mount.
  (let [el (.getElementById js/document "app")
        root (rdom/create-root el)]
    (reset! root-atom root)
    (render!))

  (bind-shortcuts!)

  ;; Sync triggers — no-op unless backend-kind is :git.
  (sync/bind-sync-triggers!))

;; Reagent auto-rerenders on app-state change.
