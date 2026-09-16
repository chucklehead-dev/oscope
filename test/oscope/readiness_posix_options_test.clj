(ns oscope.readiness-posix-options-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [oscope.managed-config-posix :as posix]
            [oscope.readiness :as readiness]))

(defn- permissions [path]
  (java.nio.file.attribute.PosixFilePermissions/toString
   (java.nio.file.Files/getPosixFilePermissions path)))

(defn native-file-gate!
  "Explicit opt-in gate: real Linux POSIX calls, no mocks and no native build.
  root is a fresh parent-owned mktemp directory; retain it for external stat."
  [root]
  (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
    (let [target (io/file root "readiness" "listener.edn")
          options {:file (str target) :instance-id "native_A"}
          a (readiness/prepare! options :local)
          running? (atom true) reads (atom 0) read-errors (atom 0)
          writer-active? (atom false) intermediate-seen (promise)
          reader* (atom nil)]
      (try
        (is (= "rwx------" (permissions (.toPath (.getParentFile target)))))
        (is (= "rw-------" (permissions (.toPath target))))
        (is (= "rw-------" (permissions (.toPath (io/file (.getParentFile target)
                                                          ".oscope-config.lock")))))
        (let [{:keys [operations lock]} (:file a)
              token ((:create-temp! operations) (.toPath target) lock)]
          (try
            (is (= "rw-------" (permissions (.toPath (io/file (.getParentFile target)
                                                              (:name token))))))
            (finally ((:delete-temp! operations) token))))
        (readiness/ready! a "127.0.0.1" 12345 "http://127.0.0.1:12345/oscope")
        (let [before (read-string (slurp target))
              fd-count #(count (.listFiles (io/file "/proc/self/fd")))
              fds (fd-count) started (System/nanoTime)
              contender (try (readiness/prepare! (assoc options :instance-id "native_B") :local)
                             nil (catch Throwable error error))]
          (is (= :file-claim-failed (:reason (ex-data contender))))
          (is (< (- (System/nanoTime) started) 250000000))
          (is (= fds (fd-count)))
          (is (= before (read-string (slurp target)))))
        (let [reader (Thread.
                      (fn []
                        (while @running?
                          (try
                            (let [record (read-string (slurp target))]
                              (when-not (and (= "native_A" (:instance-id record))
                                             (= :ready (:status record)))
                                (swap! read-errors inc))
                              (when (and @writer-active?
                                         (= "native_A" (:instance-id record))
                                         (= :ready (:status record))
                                         (= 12361 (:port record)))
                                (deliver intermediate-seen :updated-midpoint))
                              (swap! reads inc))
                            (catch Throwable _ (swap! read-errors inc))))))]
          (reset! reader* reader)
          (.start reader)
          (let [deadline (+ (System/nanoTime) 200000000)]
            (while (and (zero? @reads) (< (System/nanoTime) deadline))
              (Thread/sleep 1)))
          (reset! writer-active? true)
          (doseq [n (range 32)]
            (readiness/ready! a "127.0.0.1" (+ 12345 n)
                              (str "http://127.0.0.1:" (+ 12345 n) "/oscope"))
            ;; Hold an actual intermediate generation until the other thread
            ;; acknowledges reading it, then perform the remaining updates.
            (when (= n 16)
              (is (= :updated-midpoint
                     (deref intermediate-seen 2000 :missing-overlap)))))
          (reset! writer-active? false)
          (reset! running? false)
          (.join reader 2000)
          (is (false? (.isAlive reader)))
          (is (pos? @reads))
          (is (zero? @read-errors)))
        (is (= {:status :terminal} (readiness/terminal! a :closed)))
        (let [b (readiness/prepare! (assoc options :instance-id "native_B") :local)]
          (try
            (is (= :starting (:status (read-string (slurp target)))))
            (readiness/ready! b "127.0.0.1" 12346 "http://127.0.0.1:12346/oscope")
            (let [before (slurp target)]
              (readiness/terminal! a :closed)
              (is (= before (slurp target))))
            (finally (readiness/terminal! b :closed))))
        (is (= :terminal (:status (read-string (slurp target)))))
        (is (= :closed (:reason (read-string (slurp target)))))
        (is (= "rw-------" (permissions (.toPath target))))
        (finally
          (reset! writer-active? false)
          (reset! running? false)
          (when-let [reader @reader*] (.join reader 2000))
          (readiness/terminal! a :closed))))
    @clojure.test/*report-counters*))

(deftest default-lock-remains-blocking-and-opt-in-claim-is-nonblocking
  (let [flags (atom []) closed (atom [])]
    (with-redefs-fn
      {#'posix/supported-host? (constantly true)
       #'posix/verified-directory-fd (constantly 7)
       #'posix/stat! (fn [& _] nil)
       #'posix/c-openat (fn [& _] [8 0])
       #'posix/c-flock (fn [_ flag] (swap! flags conj flag) [0 0])
       #'posix/c-close (fn [fd] (swap! closed conj fd) [0 0])}
      #(doseq [operations [(posix/operations 4096)
                           (posix/operations 4096 {:nonblocking-lock? true})]]
         (let [target (.toPath (java.io.File. "/tmp/private-readiness/status.edn"))
               claim ((:acquire-lock! operations) target)]
           ((:release-lock! operations) claim))))
    (is (= [2 6] @flags))
    (is (= [8 7 8 7] @closed))))

(deftest nonblocking-contention-closes-unpublished-descriptors
  (let [closed (atom [])]
    (with-redefs-fn
      {#'posix/supported-host? (constantly true)
       #'posix/verified-directory-fd (constantly 7)
       #'posix/stat! (fn [& _] nil)
       #'posix/c-openat (fn [& _] [8 0])
       #'posix/c-flock (fn [_ flags] (is (= 6 flags)) [-1 11])
       #'posix/c-close (fn [fd] (swap! closed conj fd) [0 0])}
      #(is (thrown? Exception
                    ((:acquire-lock! (posix/operations 4096 {:nonblocking-lock? true}))
                     (.toPath (java.io.File. "/tmp/private-readiness/status.edn"))))))
    (is (= [8 7] @closed))))
