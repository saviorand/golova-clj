(ns golova.state.sync
  "Git-backed cross-device sync. Sibling of `storage` (which stays
  snapshot-shaped via the `Backend` protocol). This namespace is
  file-shaped: it converts the v2 snapshot to a {path → EDN-string}
  map suitable for committing to GitHub via the golova-sync Worker.

  Triggers are event-driven (visibilitychange, pagehide, Sync button),
  NOT on every mutation — see app.cljs/`bind-sync-triggers!`.

  See `/history/2026-05-23-golova-git-sync/2026-05-23-proposal.md`
  §3.4 for the architectural rationale (why Backend stays as-is and
  GitSync is a sibling, not an impl)."
  (:require [clojure.string :as str]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [golova.state.core :as core :refer [app-state]]
            [golova.state.persist :as persist]
            [golova.storage :as storage]))

;; ===========================================================================
;; Pure: snapshot ↔ files
;; ===========================================================================

;; The repo layout (mirrors ui/sidebar.cljs):
;;
;;   meta.edn                                  ; UI state + version
;;   domains/<id>/domain.edn                   ; {:id :label :parent}
;;   domains/<id>/types.edn                    ; vec of {:name :constructors}
;;   domains/<id>/predicates.edn               ; vec of {:name :argTypes}
;;   domains/<id>/queries.edn                  ; vec of {:name :text :pinned?}
;;   domains/<id>/rules.edn                    ; vec of bare clauses
;;   domains/<id>/events.edn                   ; vec of {:id :op :triple :at :source :device}

;; ---------------------------------------------------------------------------
;; Pretty-print helpers

(defn ^:private pretty
  "Pretty-print `form` to a string suitable for committing. Stable across
  runs: no print-length/level truncation, deterministic key order via
  pprint, trailing newline. Per-line right-trim because pprint's
  code-dispatch occasionally leaves trailing whitespace on some lines but
  not others, which renders as gratuitous diff noise."
  [form]
  (let [raw (with-out-str
              (binding [*print-length* nil
                        *print-level*  nil
                        pprint/*print-pprint-dispatch* pprint/code-dispatch]
                (pprint/pprint form)))]
    (->> (str/split raw #"\n")
         (map str/trimr)
         (str/join "\n")
         str/trim-newline)))

(defn ^:private edn-str
  "Pretty form + final newline. Files always end in '\\n' so editors
  don't reflow the trailing line."
  [form]
  (str (pretty form) "\n"))

(defn ^:private parse-edn
  "Read EDN string, returning [ok? value-or-error]. Doesn't throw."
  [s]
  (try
    [true (reader/read-string s)]
    (catch :default e
      [false (or (.-message e) (str e))])))

;; ---------------------------------------------------------------------------
;; Domain assignment for events

(defn ^:private subject-domain-map
  "Replay :in-domain assert/retract events to produce {subject-kw → domain-kw}.
  Last assert wins; a retract that matches the current binding clears it."
  [events]
  (reduce
    (fn [m {:keys [op triple]}]
      (let [[e a v] triple]
        (if (= :in-domain a)
          (case op
            :assert  (assoc m e v)
            :retract (if (= v (get m e)) (dissoc m e) m)
            m)
          m)))
    {}
    events))

(defn ^:private domain-meta-attr? [a] (or (= :domain-label a) (= :domain-parent a)))

(defn ^:private event-domain
  "Return the domain keyword an event belongs in, or nil if domainless.

  Rules:
    - [<dom> :domain-label  v]   → dom         (subject IS a domain)
    - [<dom> :domain-parent v]   → dom         (subject IS a domain)
    - [e :in-domain dom]         → dom         (the assignment itself)
    - everything else            → subj→dom    via the subject-domain-map"
  [{:keys [triple]} subj->dom]
  (let [[e a v] triple]
    (cond
      (domain-meta-attr? a)  e
      (= :in-domain a)       v
      :else                  (get subj->dom e))))

;; ---------------------------------------------------------------------------
;; meta.edn shape

(defn ^:private snapshot->meta
  "Top-level UI state file. Excludes :device-id (per device, not synced)
  and orphan events (those are surfaced separately so domain files are
  the only loss-vector for hand edits)."
  [snap orphan-events]
  (cond-> {:version       (or (:version snap) 2)
           :current-domain (:current-domain snap)
           :selection     (or (:selection snap) {:kind :home})
           :theme         (or (:theme snap) :light)
           :expanded      (vec (sort (or (:expanded snap) [])))
           :expanded-subs (vec (sort (or (:expanded-subs snap) [])))
           :home          (or (:home snap) {:onboarding-collapsed? true})}
    (seq orphan-events) (assoc :orphan-events (vec orphan-events))))

(defn ^:private meta->snapshot-bits
  "Inverse of snapshot->meta: pull out the snapshot fields and any orphan
  events the user/hand-edit kept here."
  [m]
  {:fields {:version        (or (:version m) 2)
            :current-domain (:current-domain m)
            :selection      (or (:selection m) {:kind :home})
            :theme          (or (:theme m) :light)
            :expanded       (vec (or (:expanded m) []))
            :expanded-subs  (mapv vec (or (:expanded-subs m) []))
            :home           (or (:home m) {:onboarding-collapsed? true})}
   :orphan-events (vec (:orphan-events m))})

;; ---------------------------------------------------------------------------
;; Domain entities (domain.edn): {:id :label :parent}
;;
;; These are DERIVED from :domain-label / :domain-parent events. On push we
;; emit domain.edn as the human-readable canonical view and ELIDE the
;; matching events from events.edn (otherwise hand-editing the label leaves
;; a contradictory event in the log). On pull we re-synthesize the events
;; from domain.edn with deterministic IDs so the in-memory event log is
;; complete again.

(defn ^:private domain-entities-from-events
  "Replay :domain-label / :domain-parent events into
  {id → {:id :label :parent :label-at :parent-at}}. Last assert wins;
  the *-at fields preserve the original event's :at so round-tripping
  through domain.edn doesn't lose timestamp provenance (otherwise the
  reconstructed event lands at epoch 0 and the activity feed regresses)."
  [events]
  (reduce
    (fn [m {:keys [op triple at]}]
      (let [[e a v] triple]
        (case [op a]
          [:assert  :domain-label]  (-> m
                                        (assoc-in [e :label]    v)
                                        (assoc-in [e :label-at] at))
          [:retract :domain-label]  (if (= v (get-in m [e :label]))
                                       (update m e dissoc :label :label-at) m)
          [:assert  :domain-parent] (-> m
                                        (assoc-in [e :parent]    v)
                                        (assoc-in [e :parent-at] at))
          [:retract :domain-parent] (if (= v (get-in m [e :parent]))
                                       (update m e dissoc :parent :parent-at) m)
          m)))
    {}
    events))

(defn ^:private domain-meta-event?
  "True for events whose triple's attribute is :domain-label / :domain-parent.
  These get stripped from events.edn since domain.edn captures the same info."
  [{:keys [triple]}]
  (domain-meta-attr? (second triple)))

;; ---------------------------------------------------------------------------
;; Sorting (determinism)
;;
;; Pretty-printed EDN diffs are only useful if the same snapshot produces
;; the same bytes. We sort every collection by a stable key.

(defn ^:private sort-by-name [items]
  (vec (sort-by (fn [x] [(str (:name x)) (count (:argTypes x))]) items)))

(defn ^:private sort-rules [rules]
  (vec (sort-by (comp pr-str :clause) rules)))

(defn ^:private sort-clauses [clauses]
  (vec (sort-by pr-str clauses)))

(defn ^:private sort-events [events]
  ;; Stable by (at, id) — preserves causal order while breaking ties.
  (vec (sort-by (juxt :at :id) events)))

;; ---------------------------------------------------------------------------
;; snapshot → files

(defn ^:private domain-rules-clauses
  "Bare clauses (without :id, :domain wrappers) for `dom-id`, sorted."
  [snap dom-id]
  (->> (:rules snap)
       (filter #(= dom-id (:domain %)))
       (sort-by (comp pr-str :clause))
       (mapv :clause)))

(defn ^:private domain-typed [snap dom-id key]
  (->> (get-in snap [:schema key])
       (filter #(= dom-id (:domain %)))
       (map #(dissoc % :domain))
       sort-by-name))

(defn ^:private domain-queries [snap dom-id]
  (->> (get-in snap [:schema :queries])
       (filter #(= dom-id (:domain %)))
       (map #(dissoc % :domain))
       (sort-by :name)
       vec))

(defn snapshot->files
  "Pure: v2 snapshot → {path → EDN-string} for the entire repo.

  Determinism: every collection is sorted by a stable key so the same
  snapshot produces byte-identical output.

  Domain-meta events (:domain-label / :domain-parent) are extracted into
  domain.edn instead of duplicated in events.edn — see comment block
  above on `domain-entities-from-events`."
  [snap]
  (let [events       (vec (or (:events snap) []))
        non-meta     (vec (remove domain-meta-event? events))
        subj->dom    (subject-domain-map non-meta)
        dom-entities (domain-entities-from-events events)
        ;; Domain ids: union of (a) ids that appear in dom-entities, (b) ids that
        ;; appear as :domain on any rule/type/pred/query, (c) :current-domain.
        decl-doms    (set (concat (map :domain (:rules snap))
                                  (map :domain (get-in snap [:schema :types]))
                                  (map :domain (get-in snap [:schema :predicates]))
                                  (map :domain (get-in snap [:schema :queries]))))
        cur-dom      (some-> snap :current-domain vector set)
        all-doms     (vec (sort (set (concat (keys dom-entities)
                                             (remove nil? decl-doms)
                                             (remove nil? (or cur-dom []))))))
        ;; Bucket events by domain.
        ev-by-dom    (group-by #(event-domain % subj->dom) non-meta)
        orphan-evs   (get ev-by-dom nil [])
        files        (atom {})]
    (swap! files assoc "meta.edn" (edn-str (snapshot->meta snap orphan-evs)))
    (doseq [dom-id all-doms]
      (let [e         (get dom-entities dom-id)
            dom-info  (cond-> {:id dom-id
                               :label  (:label  e)
                               :parent (:parent e)}
                        (:label-at  e) (assoc :label-at  (:label-at  e))
                        (:parent-at e) (assoc :parent-at (:parent-at e)))
            base      (str "domains/" (name dom-id) "/")
            dom-evs   (sort-events (get ev-by-dom dom-id []))]
        (swap! files assoc (str base "domain.edn") (edn-str dom-info))
        (swap! files assoc (str base "rules.edn")
               (edn-str (domain-rules-clauses snap dom-id)))
        (swap! files assoc (str base "types.edn")
               (edn-str (domain-typed snap dom-id :types)))
        (swap! files assoc (str base "predicates.edn")
               (edn-str (domain-typed snap dom-id :predicates)))
        (swap! files assoc (str base "queries.edn")
               (edn-str (domain-queries snap dom-id)))
        (swap! files assoc (str base "events.edn") (edn-str dom-evs))))
    @files))

;; ---------------------------------------------------------------------------
;; files → snapshot

(defn ^:private deterministic-event-id
  "Stable hex id derived from (:op :triple :at). Used when a hand-edited
  events.edn is missing :id, or when synthesizing domain-meta events from
  domain.edn during a pull."
  [{:keys [op triple at]}]
  (let [h (hash [op triple at])]
    (str "ev-" (.toString (bit-and h 0x7fffffff) 16))))

(defn ^:private fill-event-id [evt]
  (if (:id evt) evt (assoc evt :id (deterministic-event-id evt))))

(defn ^:private fill-rule-id [rule]
  (cond
    (and (map? rule) (:id rule)) rule
    (map? rule)                  (assoc rule :id (str (random-uuid)))
    :else                        {:id (str (random-uuid)) :clause rule}))

(defn ^:private domain-id-from-path
  "domains/people/rules.edn → :people"
  [path]
  (when-let [m (re-matches #"^domains/([^/]+)/.+$" path)]
    (keyword (second m))))

(defn ^:private file-kind
  "rules.edn / types.edn / predicates.edn / queries.edn / domain.edn / events.edn"
  [path]
  (let [base (last (str/split path #"/"))]
    (some #(when (= base %) (keyword (str/replace % #"\.edn$" "")))
          ["rules.edn" "types.edn" "predicates.edn"
           "queries.edn" "domain.edn" "events.edn"])))

(defn ^:private synth-domain-events
  "Given a domain.edn map, produce the :domain-label and :domain-parent
  events. Uses :label-at / :parent-at if domain.edn carries them (the
  round-trip case); falls back to 0 for hand-written domain.edn files
  with no timestamp."
  [{:keys [id label parent label-at parent-at]}]
  (let [mk (fn [a v at]
             (let [evt {:op :assert :triple [id a v]
                        :at (or at 0) :source "domain.edn"}]
               (assoc evt :id (deterministic-event-id evt))))]
    (cond-> []
      label  (conj (mk :domain-label  label  label-at))
      parent (conj (mk :domain-parent parent parent-at)))))

(defn ^:private files->snapshot-fallible
  "Pure conversion that throws on invalid input. The public wrapper
  `files->snapshot` catches and returns a richer result."
  [files]
  (let [meta-text (get files "meta.edn")
        _         (when-not meta-text
                    (throw (ex-info "missing meta.edn" {:paths (vec (keys files))})))
        [ok? m]   (parse-edn meta-text)
        _         (when-not ok?
                    (throw (ex-info (str "meta.edn unreadable: " m) {})))
        {:keys [fields orphan-events]} (meta->snapshot-bits m)
        ;; Bucket all the non-meta paths by domain.
        by-dom    (atom {})
        warnings  (atom [])
        add!      (fn [dom k v] (swap! by-dom assoc-in [dom k] v))
        warn!     (fn [path msg] (swap! warnings conj {:path path :error msg}))]
    (doseq [[path text] files
            :when       (not= path "meta.edn")]
      (let [kind   (file-kind path)
            dom-id (domain-id-from-path path)]
        (cond
          (or (nil? kind) (nil? dom-id))
          (warn! path "unrecognised path")

          :else
          (let [[ok? v] (parse-edn text)]
            (if ok?
              (add! dom-id kind v)
              (warn! path v))))))
    ;; Now assemble.
    (let [dom-ids       (sort (keys @by-dom))
          dom-meta-evts (vec (mapcat #(synth-domain-events
                                        (or (get-in @by-dom [% :domain])
                                            {:id %}))
                                     dom-ids))
          user-events   (->> dom-ids
                             (mapcat (fn [d]
                                       (->> (get-in @by-dom [d :events] [])
                                            (map fill-event-id))))
                             vec)
          all-events    (vec (concat dom-meta-evts user-events orphan-events))
          rules         (vec (mapcat
                               (fn [d]
                                 (let [clauses (or (get-in @by-dom [d :rules]) [])]
                                   (map (fn [c]
                                          (-> (fill-rule-id c)
                                              (assoc :domain d)))
                                        clauses)))
                               dom-ids))
          types         (vec (mapcat
                               (fn [d]
                                 (map #(assoc % :domain d)
                                      (or (get-in @by-dom [d :types]) [])))
                               dom-ids))
          preds         (vec (mapcat
                               (fn [d]
                                 (map #(assoc % :domain d)
                                      (or (get-in @by-dom [d :predicates]) [])))
                               dom-ids))
          queries       (vec (mapcat
                               (fn [d]
                                 (map #(assoc % :domain d)
                                      (or (get-in @by-dom [d :queries]) [])))
                               dom-ids))]
      {:snapshot (merge fields
                        {:rules  rules
                         :events all-events
                         :schema {:types      types
                                  :predicates preds
                                  :queries    queries}})
       :warnings @warnings})))

(defn files->snapshot
  "Pure inverse of snapshot->files. Returns {:snapshot ... :warnings [...]}.
  Hand-edit tolerance:
    - rules without :id → fresh UUID assigned (same pattern as decl/set-rules!)
    - events without :id → deterministic hash of (op triple at)
    - unrecognised paths or invalid EDN → recorded under :warnings, others
      still load (one bad file does not kill the whole snapshot)
    - missing meta.edn → throws (no top-level state == no snapshot)"
  [files]
  (files->snapshot-fallible files))

;; ===========================================================================
;; HTTP layer
;; ===========================================================================

(defn ^:private config []
  (let [c (:sync-config @app-state)]
    {:worker-url   (str/replace (str (:worker-url c)) #"/+$" "")
     :bearer-token (:bearer-token c)
     :owner        (:owner c)
     :repo         (:repo c)
     :branch       (:branch c)}))

(defn ^:private auth-headers []
  (let [{:keys [bearer-token]} (config)]
    #js {"Authorization" (str "Bearer " bearer-token)
         "Content-Type"  "application/json"}))

(defn ^:private target-query
  "URL-query string `?owner=…&repo=…&branch=…` for the configured target.
  Empty fields are omitted so the worker falls back to its env defaults."
  []
  (let [{:keys [owner repo branch]} (config)
        pairs (->> [["owner"  owner]
                    ["repo"   repo]
                    ["branch" branch]]
                   (remove (fn [[_ v]] (or (nil? v) (str/blank? v))))
                   (map (fn [[k v]] (str k "=" (js/encodeURIComponent v)))))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

(defn ^:private set-status! [status & [error-msg]]
  (swap! app-state update :sync-state
         (fn [s] (assoc (or s {}) :status status :error-msg (or error-msg nil)))))

(def ^:private persisted-sync-keys
  [:last-pulled-head :last-pulled-files :last-pulled-at
   :last-pushed-head :last-pushed-at :last-warnings])

(defn ^:private update-and-persist-sync-state!
  "Merge `m` into `:sync-state` and persist the durable fields to
  localStorage. After-reload, push-then-pull needs :last-pulled-head and
  :last-pulled-files to be available without a prior pull this session."
  [m]
  (swap! app-state update :sync-state (fn [s] (merge (or s {}) m)))
  (storage/save-sync-meta!
    (select-keys (:sync-state @app-state) persisted-sync-keys)))

(defn ^:private parse-json [^js resp]
  (.then (.text resp) (fn [t] (try (js->clj (.parse js/JSON t) :keywordize-keys true)
                                   (catch :default _ {:raw t})))))

(defn unkeywordize-files
  "js->clj with :keywordize-keys turns the file-path keys of the /snapshot
  response into keywords (`:domains/starter/events.edn`). The rest of the
  sync layer keys files by their string path, so undo that transformation
  here at the seam."
  [m]
  (when m
    (into {} (map (fn [[k v]] [(subs (str k) 1) v])) m)))

(defn list-repos!
  "Fetch the list of repos the worker's PAT can reach. Resolves to a vec of
  {:full-name :default-branch :private?}. Used by the Settings UI to render
  the repo-selector dropdown. Rejects with ex-info on non-2xx."
  []
  (let [{:keys [worker-url]} (config)]
    (-> (js/fetch (str worker-url "/repos")
                  #js {:method "GET"
                       :headers (auth-headers)})
        (.then (fn [^js r]
                 (if (.-ok r)
                   (-> (parse-json r)
                       (.then (fn [body]
                                (mapv (fn [r]
                                        {:full-name      (:full_name r)
                                         :default-branch (:default_branch r)
                                         :private?       (:private r)})
                                      (or (:repos body) [])))))
                   (-> (parse-json r)
                       (.then (fn [body]
                                (throw (ex-info (or (:error body) "list-repos failed")
                                                {:status (.-status r) :body body})))))))))))

(defn fetch-remote!
  "Promise of {:head-sha :files}. Rejects with an ex-info on non-2xx."
  ([] (fetch-remote! {:keepalive? false}))
  ([{:keys [keepalive?]}]
   (let [{:keys [worker-url]} (config)]
     (-> (js/fetch (str worker-url "/snapshot" (target-query))
                   #js {:method "GET"
                        :headers (auth-headers)
                        :keepalive (boolean keepalive?)})
         (.then (fn [^js r]
                  (if (.-ok r)
                    (-> (parse-json r)
                        (.then (fn [body]
                                 {:head-sha (:head_sha body)
                                  :files    (unkeywordize-files (:files body))})))
                    (-> (parse-json r)
                        (.then (fn [body]
                                 (throw (ex-info (or (:error body) "fetch failed")
                                                 {:status (.-status r)
                                                  :body   body})))))))) ))))

(defn push-remote!
  "Promise. On 201: returns {:head-sha sha}. On 409: returns {:conflict? true
  :current-head sha}. On other non-2xx: rejects with ex-info."
  [base-sha files message & [{:keys [keepalive?]}]]
  (let [{:keys [worker-url]} (config)
        payload (clj->js {:base_sha base-sha
                          :files    files
                          :message  message})]
    (-> (js/fetch (str worker-url "/commit" (target-query))
                  #js {:method  "POST"
                       :headers (auth-headers)
                       :body    (.stringify js/JSON payload)
                       :keepalive (boolean keepalive?)})
        (.then (fn [^js r]
                 (cond
                   (= 201 (.-status r))
                   (-> (parse-json r) (.then (fn [b] {:head-sha (:head_sha b)})))

                   (= 409 (.-status r))
                   (-> (parse-json r) (.then (fn [b] {:conflict?    true
                                                      :current-head (:current_head b)})))

                   :else
                   (-> (parse-json r)
                       (.then (fn [b]
                                (throw (ex-info (or (:error b) "push failed")
                                                {:status (.-status r) :body b})))))))))))

;; ===========================================================================
;; Public sync ops
;; ===========================================================================

(defn diff-files
  "Pure: returns the subset of `current` whose value differs from `last-pulled`.
  Used to send only changed files in /commit (the Worker still sends a
  complete tree because base_tree inherits unchanged paths, but minimising
  the blob upload set is the wire-cost win)."
  [last-pulled current]
  (into {}
        (for [[path text] current
              :when (not= text (get last-pulled path))]
          [path text])))

(defn ^:private empty-or-no-meta?
  "First-push case: the repo has files (e.g. a README) but no meta.edn —
  treat as 'no remote state yet, keep what's local.'"
  [files]
  (or (empty? files) (not (contains? files "meta.edn"))))

(defn ^:private apply-pulled-snapshot! [{:keys [head-sha files]}]
  (let [now (.now js/Date)]
    (cond
      (empty-or-no-meta? files)
      ;; First-push case: don't clobber local state. Record the head sha so
      ;; the next push has a base, and stash an empty :last-pulled-files so
      ;; diff-files will include the entire current snapshot.
      (do (update-and-persist-sync-state!
            {:last-pulled-head  head-sha
             :last-pulled-files {}
             :last-pulled-at    now
             :last-warnings     []})
          {:warnings [] :head-sha head-sha :first-push? true})

      :else
      (let [{:keys [snapshot warnings]} (files->snapshot files)]
        (when (seq warnings)
          (js/console.warn "sync: pulled files have warnings"
                           (clj->js warnings)))
        (persist/import-snapshot! snapshot)
        (update-and-persist-sync-state!
          {:last-pulled-head  head-sha
           :last-pulled-files files
           :last-pulled-at    now
           :last-warnings     (vec warnings)})
        {:warnings warnings :head-sha head-sha}))))

(defn sync-pull!
  "Pull remote, replace local state, rebuild. Returns a Promise resolving to
  {:head-sha :warnings}. On error sets :sync-state :status :error."
  []
  (set-status! :pulling)
  (-> (fetch-remote!)
      (.then (fn [{:keys [head-sha files]}]
               (let [r (apply-pulled-snapshot! {:head-sha head-sha :files files})]
                 (set-status! :idle)
                 r)))
      (.catch (fn [^js e]
                (set-status! :error (or (.-message e) (str e)))
                (throw e)))))

(defn sync-push!
  "Build snapshot → files, POST /commit. On 409 trigger sync-pull! (LWW
  clobber) and resolve with {:conflict? true}. Returns a Promise.

  `opts`:
    :keepalive? — pass to fetch (use during visibilitychange=hidden so the
                  request survives the page-transition)."
  ([] (sync-push! {}))
  ([{:keys [keepalive?] :as _opts}]
   (set-status! :pushing)
   (let [snap          (core/serializable @app-state)
         files         (snapshot->files snap)
         sync-state    (:sync-state @app-state)
         base-sha      (:last-pulled-head sync-state)
         last-pulled   (:last-pulled-files sync-state {})
         changed       (diff-files last-pulled files)]
     (cond
       (nil? base-sha)
       (do (set-status! :error "no base sha — pull first")
           (js/Promise.reject (ex-info "no base sha; run sync-pull! first" {})))

       (empty? changed)
       (do (set-status! :idle)
           (js/Promise.resolve {:no-op? true}))

       :else
       (-> (push-remote! base-sha changed
                         (str "golova sync " (.toISOString (js/Date.)))
                         {:keepalive? keepalive?})
           (.then (fn [{:keys [head-sha conflict? current-head]}]
                    (cond
                      conflict?
                      (do (js/console.warn "sync: 409, refetching")
                          (-> (sync-pull!)
                              (.then (fn [_] {:conflict? true
                                              :current-head current-head}))))

                      :else
                      (let [now (.now js/Date)]
                        (update-and-persist-sync-state!
                          {:last-pushed-head head-sha
                           :last-pushed-at   now
                           :last-pulled-head head-sha
                           :last-pulled-files files
                           :last-pulled-at   now})
                        (set-status! :idle)
                        {:head-sha head-sha}))))
           (.catch (fn [^js e]
                     (set-status! :error (or (.-message e) (str e)))
                     (throw e))))))))

(defn sync!
  "Sync-button handler. Push-first when we have a base-sha to push against
  (the common case after at least one prior pull, persisted via
  `storage/sync-meta`). Falls back to pull-first only on the truly-first
  sync of a new device, where there's no base-sha to push against.

  Push-first preserves local edits in the single-active-device case: your
  changes land on the remote BEFORE any potentially-destructive pull.
  Concurrent-edit case (409) still falls into the existing LWW pull-clobber
  in sync-push!."
  []
  (if (some? (:last-pulled-head (:sync-state @app-state)))
    (-> (sync-push!) (.then (fn [_] (sync-pull!))))
    (-> (sync-pull!) (.then (fn [_] (sync-push!))))))

(defn pending-changes
  "How many files differ between the current local snapshot and the last
  successfully-pulled file set. Returns
    {:status :synced}              — last-pulled known, zero diff
    {:status :pending :count N}    — last-pulled known, N files would push
    {:status :unknown}             — never pulled this session; can't tell.
  Pure read against `app-state`; safe to call on every render."
  []
  (let [s (:sync-state @app-state)]
    (if (nil? (:last-pulled-head s))
      {:status :unknown}
      (let [files   (snapshot->files (core/serializable @app-state))
            changed (diff-files (:last-pulled-files s {}) files)
            n       (count changed)]
        (if (zero? n)
          {:status :synced}
          {:status :pending :count n})))))

;; ===========================================================================
;; Trigger plumbing
;; ===========================================================================

(defn ^:private silent
  "Wrap a sync op so that thrown errors don't surface as unhandled-promise
  rejections — they're already captured in :sync-state :status :error."
  [p-fn]
  (fn [& args]
    (-> (apply p-fn args)
        (.catch (fn [e]
                  (js/console.warn "sync trigger error:" (or (.-message e) (str e)))
                  nil)))))

(defonce ^:private trigger-handles (atom nil))

(defn unbind-sync-triggers!
  "Remove any previously-registered listeners. Safe to call multiple times."
  []
  (when-let [{:keys [vis-handler hide-handler]} @trigger-handles]
    (.removeEventListener js/document "visibilitychange" vis-handler)
    (.removeEventListener js/window   "pagehide"        hide-handler)
    (reset! trigger-handles nil)))

(defn bind-sync-triggers!
  "Wire visibilitychange (visible→pull, hidden→push) and pagehide (push).
  Idempotent — calling twice unbinds the first set first. No-op when
  backend-kind is not :git."
  []
  (unbind-sync-triggers!)
  (when (= :git (:backend-kind @app-state))
    (let [vis  (fn []
                 (case (.-visibilityState js/document)
                   "visible" ((silent sync-pull!))
                   "hidden"  ((silent sync-push!) {:keepalive? true})
                   nil))
          hide (fn [_]
                 ;; pagehide is best-effort; use sendBeacon when available so
                 ;; the request survives even the tab teardown.
                 ((silent sync-push!) {:keepalive? true}))]
      (.addEventListener js/document "visibilitychange" vis)
      (.addEventListener js/window   "pagehide"        hide)
      (reset! trigger-handles {:vis-handler vis :hide-handler hide}))))
