(ns oscope.managed-config-store-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oscope.config :as config]
            [oscope.managed-config-store :as store]))

(def properties {"os.name" "Linux" "user.home" "/home/operator"})
(def managed-path "/home/operator/.config/oscope/config.edn")

(defn- document [port]
  (assoc-in config/defaults [:server :port] port))

(defn- encoded [document]
  (.getBytes (config/encode document) "UTF-8"))

(defn- fake-operations
  ([state] (fake-operations state nil))
  ([state fail-at]
   (let [fail! (fn [stage]
                 (swap! (:events state) conj stage)
                 (when (= stage fail-at)
                   (throw (ex-info (str "private-canary-" (name stage))
                                   {:secret "private-value"}))))]
     {:supported? true
      :read-bytes (fn [_ _]
                    (fail! :read)
                    @(:live state))
      :ensure-directory! (fn [_] (fail! :directory) :directory)
      :acquire-lock! (fn [_] (fail! :lock) :lock)
      :release-lock! (fn [_] (fail! :release-lock) true)
      :create-temp! (fn [_ _] (fail! :temp) :temp)
      :write! (fn [_ bytes]
                (fail! :write)
                (reset! (:temp state) bytes))
      :force-file! (fn [_] (fail! :force))
      :verify-temp! (fn [_] (fail! :permission))
      :read-temp! (fn [_]
                    (fail! :readback)
                    @(:temp state))
      :atomic-replace! (fn [_ _ _]
                         (fail! :move)
                         (let [bytes @(:temp state)]
                           (reset! (:live state) bytes)
                           (reset! (:temp state) nil)))
      :verify-target! (fn [_ _] (fail! :target-permission))
      :force-directory! (fn [_ _] (fail! :directory-force))
      :delete-temp! (fn [_]
                      (fail! :delete-temp)
                      (reset! (:temp state) nil))})))

(defn- state
  ([] (state nil))
  ([live]
   {:live (atom live) :temp (atom nil) :events (atom [])}))

(defn- managed
  ([state] (managed state nil))
  ([state fail-at]
   (store/managed-store [] {} properties
                        (fn [path] (and @(:live state) (= managed-path path)))
                        (fake-operations state fail-at))))

(defn- public-result? [value]
  (and (map? value)
       (every? #{:oscope.managed-config-store/version :status :origin
                 :reason :revision}
               (keys value))
       (not (str/includes? (pr-str value) "private"))
       (not (str/includes? (pr-str value) managed-path))))

(deftest absent-snapshot-and-successful-restart-use-opaque-revisions
  (let [state (state)
        first-store (managed state)
        absent (store/snapshot! first-store)
        saved (store/replace! first-store (:revision absent) (document 14318))
        restarted (managed state)
        rediscovered (store/snapshot! restarted)]
    (is (= :none (store/selection-origin first-store)))
    (is (= {:oscope.managed-config-store/version 1
            :status :ok :origin :none
            :revision store/absent-revision}
           absent))
    (is (str/starts-with? (:revision absent) "sha256:"))
    (is (= :ok (:status saved)))
    (is (str/starts-with? (:revision saved) "sha256:"))
    (is (not= (:revision absent) (:revision saved)))
    (is (= :managed-user (store/selection-origin restarted)))
    (is (= (:revision saved) (:revision rediscovered)))
    (is (= (document 14318)
           (-> (String. @(:live state) "UTF-8")
               config/parse config/file-document config/validate)))
    (is (nil? @(:temp state)))
    (is (every? public-result? [absent saved rediscovered]))))

(deftest revisions-follow-canonical-bytes-rather-than-map-order
  (let [left (into (array-map) config/defaults)
        right (into (array-map) (reverse (seq config/defaults)))
        left-state (state (encoded left))
        right-state (state (encoded right))]
    (is (= (:revision (store/snapshot! (managed left-state)))
           (:revision (store/snapshot! (managed right-state)))))))

(deftest validation-and-size-fail-before-any-filesystem-effect
  (let [state (state)
        managed (managed state)
        invalid (store/replace! managed store/absent-revision
                                (assoc config/defaults :unknown "private-canary"))
        oversized (store/replace!
                   managed store/absent-revision
                   (assoc-in config/defaults [:storage :path]
                             (apply str (repeat (inc store/max-config-bytes) "x"))))]
    (is (= :invalid-document (:reason invalid)))
    (is (= :invalid-document (:reason oversized)))
    (is (empty? @(:events state)))
    (is (every? public-result? [invalid oversized]))))

(deftest stale-writers-cannot-both-replace-the-same-revision
  (let [state (state (encoded (document 4318)))
        first-store (managed state)
        second-store (managed state)
        prior (:revision (store/snapshot! first-store))
        first-result (store/replace! first-store prior (document 4319))
        second-result (store/replace! second-store prior (document 4320))]
    (is (= :ok (:status first-result)))
    (is (= :conflict (:reason second-result)))
    (is (= (document 4319)
           (-> (String. @(:live state) "UTF-8") config/parse config/validate)))
    (is (every? public-result? [first-result second-result]))))

(deftest successful-publication-holds-the-lock-across-reread-and-durability
  (let [state (state)
        managed (managed state)
        revision (:revision (store/snapshot! managed))]
    (reset! (:events state) [])
    (is (= :ok (:status (store/replace! managed revision (document 14318)))))
    (is (= [:directory :lock :read :temp :write :force :permission
            :readback :move :target-permission :directory-force :release-lock]
           @(:events state)))))

(deftest malformed-revisions-fail-before-filesystem-effects
  (doseq [revision [nil "" "sha256:not-a-digest" (apply str (repeat 64 "a"))]]
    (let [state (state)
          result (store/replace! (managed state) revision (document 4318))]
      (is (= :invalid-revision (:reason result)))
      (is (empty? @(:events state)))
      (is (public-result? result)))))

(deftest explicit-command-line-and-environment-files-are-never-written
  (doseq [[arguments environment expected-origin]
          [[["--config" "/private/cli.edn"] {} :command-line]
           [[] {"OSCOPE_CONFIG" "/private/environment.edn"} :environment]]]
    (let [state (state)
          candidate (store/managed-store arguments environment properties
                                         (constantly true)
                                         (fake-operations state))
          result (store/replace! candidate store/absent-revision (document 1))]
      (is (= expected-origin (store/selection-origin candidate)))
      (is (= :explicit-selection (:reason result)))
      (is (= :explicit-selection (:reason (store/snapshot! candidate))))
      (is (empty? @(:events state)))
      (is (public-result? result)))))

(deftest oversized-existing-files-fail-as-unreadable
  (let [state (state (byte-array (inc store/max-config-bytes)))
        result (store/snapshot! (managed state))]
    (is (= :unreadable (:reason result)))
    (is (public-result? result))))

(deftest pre-publication-failures-preserve-the-previous-valid-file
  (doseq [failure [:write :force :permission :readback :move]]
    (testing (name failure)
      (let [before (encoded (document 4318))
            state (state before)
            prior (:revision (store/snapshot! (managed state)))
            candidate (managed state failure)
            result (store/replace! candidate prior (document 4319))]
        (is (= (seq before) (seq @(:live state))))
        (is (= :error (:status result)))
        (is (public-result? result))
        (is (nil? @(:temp state)))))))

(deftest cleanup-failure-never-masks-a-primary-failure
  (let [before (encoded (document 4318))
        state (state before)
        base (fake-operations state :write)
        operations (assoc base :delete-temp!
                          (fn [_ _]
                            (throw (ex-info "cleanup-private-canary" {}))))
        managed (store/managed-store [] {} properties (constantly true) operations)
        prior (:revision (store/snapshot! managed))
        result (store/replace! managed prior (document 4319))]
    (is (= :write-failed (:reason result)))
    (is (public-result? result))))

(deftest cleanup-only-failure-is-a-fixed-error-after-publication
  (let [before (encoded (document 4318))
        state (state before)
        base (fake-operations state)
        operations (assoc base :release-lock!
                          (fn [_]
                            (throw (ex-info "cleanup-private-canary" {}))))
        managed (store/managed-store [] {} properties (constantly true) operations)
        prior (:revision (store/snapshot! managed))
        result (store/replace! managed prior (document 4319))]
    (is (= :cleanup-failed (:reason result)))
    (is (= (seq (encoded (document 4319))) (seq @(:live state))))
    (is (public-result? result))))

(deftest symlink-and-operation-exceptions-are-fixed-and-redacted
  (let [state (state (encoded (document 4318)))
        operations (assoc (fake-operations state)
                          :read-bytes
                          (fn [_ _]
                            (throw (ex-info "private-symlink-canary"
                                            {:managed-reason :unsafe-target
                                             :path managed-path}))))
        managed (store/managed-store [] {} properties (constantly true) operations)
        snapshot (store/snapshot! managed)]
    (is (= :unsafe-target (:reason snapshot)))
    (is (public-result? snapshot))))

(deftest unsupported-hosts-fail-closed-after-pure-validation
  (let [state (state)
        managed (store/managed-store [] {} properties (constantly false)
                                     {:supported? false})
        result (store/replace! managed store/absent-revision (document 14318))]
    (is (= :unsupported-platform (:reason result)))
    (is (public-result? result))))

(deftest unavailable-managed-paths-become-fixed-results
  (let [state (state)
        managed (store/managed-store
                 [] {} {"os.name" "Linux" "user.home" "relative"}
                 (constantly false) (fake-operations state))
        result (store/snapshot! managed)]
    (is (= :managed-path-unavailable (:reason result)))
    (is (empty? @(:events state)))
    (is (public-result? result))))
