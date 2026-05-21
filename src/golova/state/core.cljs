(ns golova.state.core
  "The app-state atom and primitive helpers used by every other state.*
  module. Kept at the bottom of the dependency tree (only reagent +
  storage) so anything else can require it without risking cycles."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [golova.storage :as storage]))

(defonce app-state
  (r/atom
   {:device-id nil
    :backend nil
    ;; Data layer
    :db nil
    :db-schema nil
    :events []
    :rules []
    :schema {:types [] :predicates [] :queries []}
    :rejections []
    :build-error nil
    ;; UI state
    :current-domain nil           ; keyword, the focused domain atom
    :selection {:kind :home}
    :theme :light
    :expanded #{}                 ; set of expanded domain keywords
    :expanded-subs #{}            ; set of [domain-key sub-key] tuples
    :modal nil
    :popover nil
    :palette nil
    :top-query {:text "" :result nil}
    :home {:onboarding-collapsed? true}
    :last-saved nil
    :error nil
    :first-run? false}))

(defn current-id [] (:current-domain @app-state))
(defn device-id [] (:device-id @app-state))

(defn safe-name [s]
  (-> (or s "") str/lower-case
      (str/replace #"[^a-z0-9_-]+" "_")
      (str/replace #"^_+|_+$" "")))

(defn slugify
  "Slug a free-text string into a keyword-safe form. Preserves Unicode
  letters and numbers (Cyrillic / CJK / accented names slug to their own
  script rather than collapsing to empty) — only punctuation, whitespace,
  and emoji become '-'."
  [s]
  (-> (or s "") str/lower-case
      (str/replace (js/RegExp. "[^\\p{L}\\p{N}]+" "gu") "-")
      (str/replace #"^-+|-+$" "")))

(defn mk-event
  ([op data] (mk-event op data nil))
  ([op data source]
   (merge {:id (str (random-uuid))
           :device (device-id)
           :at (.now js/Date)
           :op op
           :source source}
          data)))

(defn serializable [state]
  {:device-id (:device-id state)
   :version 2                                     ; schema/persistence shape version
   :current-domain (:current-domain state)
   :selection (:selection state)
   :theme (:theme state)
   :expanded (vec (:expanded state))
   :expanded-subs (vec (:expanded-subs state))
   :home (:home state)
   :rules (:rules state)
   :events (:events state)
   :schema (:schema state)})

(defn save! []
  (when-let [b (:backend @app-state)]
    (storage/-save b (serializable @app-state))
    (swap! app-state assoc :last-saved (.now js/Date))))
