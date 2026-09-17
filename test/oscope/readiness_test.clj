(ns oscope.readiness-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.readiness :as readiness]))

(defn fake-file []
  (let [owner (atom nil) current (atom nil) events (atom [])]
    {:owner owner :current current :events events
     :operations
     {:supported? true
      :ensure-directory! (fn [_] (swap! events conj :directory))
      :acquire-lock! (fn [_]
                       (let [claim (Object.)]
                         (when-not (compare-and-set! owner nil claim)
                           (throw (ex-info "private path credential" {})))
                         (swap! events conj :claim)
                         claim))
      :release-lock! (fn [claim]
                       (is (identical? @owner claim))
                       (reset! owner nil)
                       (swap! events conj :release))
      :create-temp! (fn [_ claim]
                      (is (identical? @owner claim))
                      (swap! events conj :temp)
                      (atom nil))
      :write! (fn [temp bytes]
                (reset! temp (read-string (String. bytes "UTF-8")))
                (swap! events conj :write))
      :force-file! (fn [_] (swap! events conj :force-file))
      :verify-temp! (fn [_] (swap! events conj :verify-temp))
      :atomic-replace! (fn [temp _ claim]
                         (is (identical? @owner claim))
                         (reset! current @temp)
                         (swap! events conj :atomic-replace))
      :verify-target! (fn [& _] (swap! events conj :verify-target))
      :force-directory! (fn [& _] (swap! events conj :force-directory))
      :delete-temp! (fn [_] (swap! events conj :delete-temp))}}))

(deftest opt-in-shape-rejects-without-values-or-native-loading
  (let [loaded (atom 0)]
    (with-redefs [readiness/file-operations #(swap! loaded inc)]
      (is (nil? (readiness/prepare! nil :local)))
      (doseq [options [false {} {:publish! false} {:file "/private-secret"}
                       {:file "/private-secret" :instance-id "bad secret"}
                       {:publish! identity :extra "private-secret"}
                       {:publish! identity :instance-id false}]]
        (let [error (try (readiness/prepare! options :local)
                         nil (catch Throwable error error))]
          (is (= :invalid-options (:reason (ex-data error))))
          (is (= {:oscope.readiness/error true :reason :invalid-options}
                 (ex-data error)))
          (is (nil? (.getCause error)))))
      (is (zero? @loaded)))))

(deftest callback-and-channel-adapters-observe-bounded-lifecycle
  (let [snapshot (atom nil) channel (atom [])
        publish (fn [record] (reset! snapshot record) (swap! channel conj record))
        handle (readiness/prepare! {:publish! publish :instance-id "launch_A"} :durable)]
    (is (= :starting (:status @snapshot)))
    (readiness/ready! handle "127.0.0.1" 12345 "http://127.0.0.1:12345/oscope/telemetry")
    (is (= #{:oscope.readiness/version :instance-id :status :storage-mode
             :host :port :url} (set (keys @snapshot))))
    (is (= :durable (:storage-mode @snapshot)))
    (is (= 12345 (:port @snapshot)))
    (readiness/terminal! handle :stopping)
    (is (= :terminal (:status @snapshot)))
    (readiness/terminal! handle :closed)
    (readiness/terminal! handle :closed)
    (is (= [:starting :ready :terminal :terminal] (mapv :status @channel)))
    (is (= :closed (:reason @snapshot)))
    (is (thrown? Exception
                 (readiness/ready! handle "127.0.0.1" 12345 "ignored")))))

(deftest nonblocking-lifetime-claim-and-old-stop-generation-guard
  (let [{:keys [operations current owner events]} (fake-file)
        seen (atom [])]
    (reset! current {:instance-id "dead_process" :status :ready})
    (with-redefs [readiness/file-operations (constantly operations)]
      (let [a (readiness/prepare!
               {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                :publish! #(swap! seen conj %)} :local)]
        (is (= :starting (:status @current)))
        (is (= "A" (:instance-id @current)))
        (is (= [:directory :claim :temp :write :force-file :verify-temp
                :atomic-replace :verify-target :force-directory :delete-temp]
               @events))
        (readiness/ready! a "127.0.0.1" 12345 "http://127.0.0.1:12345/oscope")
        (let [before @current
              error (try (readiness/prepare!
                          {:file "/tmp/readiness-test/A.edn" :instance-id "B"}
                          :local)
                         nil (catch Throwable error error))]
          (is (= :file-claim-failed (:reason (ex-data error))))
          (is (nil? (.getCause error)))
          (is (= before @current)))
        (readiness/terminal! a :stopping)
        (is (some? @owner))
        (readiness/terminal! a :closed)
        (is (nil? @owner))
        (let [b (readiness/prepare!
                 {:file "/tmp/readiness-test/A.edn" :instance-id "B"} :local)]
          (readiness/ready! b "127.0.0.1" 12346 "http://127.0.0.1:12346/oscope")
          (let [before @current]
            (readiness/terminal! a :closed)
            (readiness/terminal! a :startup-failed)
            (is (= before @current)))
          (readiness/terminal! b :closed)
          (is (= 2 (count (filter #{:release} @events)))))))))

(deftest callback-failure-never-leaves-ready-or-held-claim
  (let [{:keys [operations current owner]} (fake-file)
        attempts (atom [])]
    (with-redefs [readiness/file-operations (constantly operations)]
      (let [handle (readiness/prepare!
                    {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                     :publish! (fn [record]
                                 (swap! attempts conj (:status record))
                                 (when (= :ready (:status record))
                                   (throw (ex-info "credential/private" {}))))}
                    :local)
            error (try (readiness/ready! handle "127.0.0.1" 12345
                                        "http://127.0.0.1:12345/oscope")
                       nil (catch Throwable error error))]
        (is (= :publication-failed (:reason (ex-data error))))
        (is (nil? (.getCause error)))
        ;; Production listener catch invokes this after revoking authority.
        (readiness/terminal! handle :startup-failed)
        (is (= :terminal (:status @current)))
        (is (= :startup-failed (:reason @current)))
        (is (nil? @owner))
        (is (= [:starting :ready :terminal] @attempts))))))

(deftest initial-publication-failure-keeps-retry-owner-until-terminal-sink-recovers
  (let [{:keys [operations current owner]} (fake-file)]
    (with-redefs [readiness/file-operations (constantly operations)]
      (let [broken? (atom true)
            error (try (readiness/prepare!
                        {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                         :publish! #(when @broken?
                                      (throw (ex-info "credential/private" {:record %})))}
                        :local)
                       nil (catch Throwable error error))]
        (is (= :oscope.readiness/startup-cleanup-incomplete (:type (ex-data error))))
        (is (nil? (.getCause error)))
        (is (= :startup-failed (:reason @current)))
        (is (some? @owner))
        (reset! broken? false)
        (is (= {:status :closed :phase :closed} ((:retry-stop! (ex-data error)))))
        (is (nil? @owner))))))

(defn ambiguous-release-file []
  (let [{:keys [operations owner] :as file} (fake-file)
        releases (atom 0) reused-descriptor (Object.)]
    (assoc file :releases releases :reused-descriptor reused-descriptor
           :operations
           (assoc operations :release-lock!
                  (fn [claim]
                    (swap! releases inc)
                    (is (identical? @owner claim))
                    ;; Model close taking effect and its numeric slot being
                    ;; reused BEFORE the caller observes an ambiguous error.
                    (reset! owner reused-descriptor)
                    (throw (ex-info "private-release-canary" {:claim claim})))))))

(def unconfirmed-release-result
  {:status :closing :phase :readiness-claim-release-unconfirmed
   :retryable? false
   :errors [{:type :oscope.readiness/claim-release-unconfirmed}]})

(deftest reentrant-terminal-callback-releases-only-once
  (let [{:keys [operations owner current]} (fake-file)
        handle* (atom nil) entered? (atom false) releases (atom 0)
        reused-descriptor (Object.) damaged? (atom false)]
    (with-redefs [readiness/file-operations
                  (constantly
                   (assoc operations :release-lock!
                          (fn [claim]
                            (swap! releases inc)
                            (if (identical? @owner claim)
                              (reset! owner reused-descriptor)
                              (reset! damaged? true)))))]
      (reset! handle*
              (readiness/prepare!
               {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                :publish! (fn [record]
                            (when (and (= :closed (:reason record))
                                       (compare-and-set! entered? false true))
                              (readiness/terminal! @handle* :closed)))} :local))
      (is (= {:status :terminal} (readiness/terminal! @handle* :closed)))
      (is (= 1 @releases))
      (is (false? @damaged?))
      (is (identical? reused-descriptor @owner))
      (is (= :terminal (:status @current)))
      (is (= {:status :terminal} (readiness/terminal! @handle* :closed)))
      (is (= 1 @releases)))))

(deftest reentrant-terminal-callback-cannot-republish-ready
  (let [{:keys [operations owner current]} (fake-file)
        handle* (atom nil) entered? (atom false) rejected (atom nil)]
    (with-redefs [readiness/file-operations (constantly operations)]
      (reset! handle*
              (readiness/prepare!
               {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                :publish! (fn [record]
                            (when (and (= :closed (:reason record))
                                       (compare-and-set! entered? false true))
                              (reset! rejected
                                      (try (readiness/ready!
                                            @handle* "127.0.0.1" 12346
                                            "http://127.0.0.1:12346/oscope")
                                           nil
                                           (catch Throwable error error)))))} :local))
      (readiness/ready! @handle* "127.0.0.1" 12345 "http://127.0.0.1:12345/oscope")
      (is (= {:status :terminal} (readiness/terminal! @handle* :closed)))
      (is (= :retired-generation (:reason (ex-data @rejected))))
      (is (nil? (some-> @rejected .getCause)))
      (is (= :terminal (:status @current)))
      (is (= :closed (:reason @current)))
      (is (nil? @owner)))))

(deftest ready-callback-stopping-retires-both-records-before-release
  (let [{:keys [operations owner current]} (fake-file)
        handle* (atom nil) entered? (atom false) releases (atom 0)
        release! (:release-lock! operations)]
    (with-redefs [readiness/file-operations
                  (constantly (assoc operations :release-lock!
                                     (fn [claim] (swap! releases inc) (release! claim))))]
      (reset! handle*
              (readiness/prepare!
               {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                :publish! (fn [record]
                            (when (and (= :ready (:status record))
                                       (compare-and-set! entered? false true))
                              (readiness/terminal! @handle* :stopping)))} :local))
      (let [error (try (readiness/ready! @handle* "127.0.0.1" 12345
                                       "http://127.0.0.1:12345/oscope")
                       nil (catch Throwable error error))]
        (is (= :publication-failed (:reason (ex-data error))))
        (is (nil? (some-> error .getCause))))
      (is (= :terminal (:status @current)))
      (is (= :stopping (:reason @current)))
      (is (= @current @(:record @handle*)))
      (is (some? @owner))
      (is (zero? @releases))
      (let [before @current
            error (try (readiness/ready! @handle* "127.0.0.1" 12346 "ignored")
                       nil (catch Throwable error error))]
        (is (= :retired-generation (:reason (ex-data error))))
        (is (= before @current)))
      (is (= {:status :closed :phase :closed}
             (readiness/finish-stop! @handle* {:status :closed :phase :closed})))
      (is (nil? @owner))
      (is (= 1 @releases))
      (is (= :terminal (:status @current)))
      (is (= :closed (:reason @current))))))

(deftest after-effect-claim-release-is-sticky-and-never-closes-reused-owner
  (let [{:keys [operations owner current releases reused-descriptor]}
        (ambiguous-release-file)]
    (with-redefs [readiness/file-operations (constantly operations)]
      (let [handle (readiness/prepare!
                    {:file "/tmp/readiness-test/A.edn" :instance-id "A"} :local)]
        (readiness/ready! handle "127.0.0.1" 12345 "http://127.0.0.1:12345/oscope")
        (dotimes [_ 3]
          ;; Old code reports retryable-looking publication failure: causal RED.
          (is (= unconfirmed-release-result
                 (readiness/finish-stop! handle {:status :closed :phase :closed}))))
        (is (= 1 @releases))
        (is (identical? reused-descriptor @owner))
        (is (= {:oscope.readiness/version 1 :instance-id "A" :status :terminal
                :storage-mode :local :host "127.0.0.1" :port 12345
                :url "http://127.0.0.1:12345/oscope" :reason :closed} @current))
        (let [error (try (readiness/ready! handle "127.0.0.1" 12346 "ignored")
                         (catch Throwable error error))]
          (is (= :retired-generation (:reason (ex-data error)))))
        (is (not (.contains (pr-str unconfirmed-release-result) "private-release-canary")))))))

(deftest startup-retry-owner-preserves-unconfirmed-release-without-repeating-effects
  (let [{:keys [operations owner releases reused-descriptor]} (ambiguous-release-file)
        retired (atom 0)]
    (with-redefs [readiness/file-operations (constantly operations)]
      (let [handle (readiness/prepare!
                    {:file "/tmp/readiness-test/A.edn" :instance-id "A"} :local)
            error (try (readiness/fail-startup!
                        handle (ex-info "private-startup-canary" {})
                        [[:listener #(swap! retired inc)]])
                       (catch Throwable error error))
            retry-stop! (:retry-stop! (ex-data error))]
        (is (= :oscope.readiness/startup-cleanup-incomplete (:type (ex-data error))))
        (is (nil? (.getCause error)))
        (is (fn? retry-stop!))
        (dotimes [_ 3] (is (= unconfirmed-release-result (retry-stop!))))
        (is (= 1 @retired))
        (is (= 1 @releases))
        (is (identical? reused-descriptor @owner))))))

(deftest before-effect-terminal-sink-failure-remains-retryable
  (let [{:keys [operations owner]} (fake-file)
        broken? (atom true) published (atom []) releases (atom 0)
        release! (:release-lock! operations)]
    (with-redefs [readiness/file-operations
                  (constantly (assoc operations :release-lock!
                                     (fn [claim] (swap! releases inc) (release! claim))))]
      (let [handle (readiness/prepare!
                    {:file "/tmp/readiness-test/A.edn" :instance-id "A"
                     :publish! (fn [record]
                                 (when (and @broken? (= :closed (:reason record)))
                                   (throw (ex-info "private-sink-canary" {})))
                                 (swap! published conj (:reason record)))} :local)]
        (is (= {:status :closing :phase :publishing-readiness
                :errors [{:type :oscope.readiness/terminal-publication-failed}]}
               (readiness/finish-stop! handle {:status :closed :phase :closed})))
        (is (some? @owner))
        (is (zero? @releases))
        (is (not-any? #{:closed} @published))
        (reset! broken? false)
        (is (= {:status :closed :phase :closed}
               (readiness/finish-stop! handle {:status :closed :phase :closed})))
        (is (nil? @owner))
        (is (= 1 @releases))
        (is (= 1 (count (filter #{:closed} @published))))))))
