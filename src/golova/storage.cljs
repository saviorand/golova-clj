(ns golova.storage
  "Persistence backends. The protocol is shaped for Drive (per-device files in
  a folder) but the only backend today is localStorage."
  (:require [cljs.pprint :as pprint]
            [cljs.reader :as reader]))

;; ---------------------------------------------------------------------------
;; Backend protocol

(defprotocol Backend
  (-load    [b]
    "Return the persisted snapshot map, or nil. Must round-trip with -save.")
  (-save    [b snapshot]
    "Persist the snapshot map. Idempotent.")
  (-clear   [b]))

;; ---------------------------------------------------------------------------
;; LocalStorage

(def ^:private state-key        "golova.state.v1")
(def ^:private device-key       "golova.device-id")
(def ^:private backend-kind-key "golova.backend-kind")
(def ^:private sync-config-key  "golova.sync-config")

(defrecord LocalStorage []
  Backend
  (-load [_]
    (when-let [raw (.getItem js/localStorage state-key)]
      (try (reader/read-string raw)
           (catch :default e
             (js/console.warn "load: bad snapshot, ignoring" e)
             nil))))
  (-save [_ snapshot]
    (.setItem js/localStorage state-key (pr-str snapshot)))
  (-clear [_]
    (.removeItem js/localStorage state-key)))

(defn local []
  (->LocalStorage))

;; ---------------------------------------------------------------------------
;; Device identity (stable per-browser/device, used to tag events)

(defn ensure-device-id! []
  (or (.getItem js/localStorage device-key)
      (let [id (str (random-uuid))]
        (.setItem js/localStorage device-key id)
        id)))

;; ---------------------------------------------------------------------------
;; Sync settings (per-device, NOT part of the snapshot — bearer token must
;; never end up in the synced files on GitHub).

(defn load-backend-kind
  "Read :backend-kind from its own localStorage key. Defaults to :local."
  []
  (let [raw (.getItem js/localStorage backend-kind-key)]
    (case raw
      "git"   :git
      "local" :local
      :local)))

(defn save-backend-kind! [k]
  (.setItem js/localStorage backend-kind-key (name k)))

(defn load-sync-config
  "Read :sync-config (map with :worker-url :bearer-token :branch) or nil."
  []
  (when-let [raw (.getItem js/localStorage sync-config-key)]
    (try (reader/read-string raw)
         (catch :default _ nil))))

(defn save-sync-config! [cfg]
  (.setItem js/localStorage sync-config-key (pr-str cfg)))

;; ---------------------------------------------------------------------------
;; Export / import — for moving the log between devices manually until Drive
;; sync lands.

(defn snapshot->json
  "Pretty-format the snapshot for download. EDN under the hood — we keep
  the file extension .edn and let humans read it."
  [snapshot]
  (with-out-str
    (binding [*print-length* nil
              *print-level*  nil]
      (pprint/pprint snapshot))))

(defn parse-snapshot [text]
  (reader/read-string text))

(defn download-blob! [filename text]
  (let [blob (js/Blob. #js [text] #js {:type "text/plain"})
        url  (.createObjectURL js/URL blob)
        a    (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (.appendChild (.-body js/document) a)
    (.click a)
    (.removeChild (.-body js/document) a)
    (.revokeObjectURL js/URL url)))
