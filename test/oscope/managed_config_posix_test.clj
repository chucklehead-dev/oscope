(ns oscope.managed-config-posix-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.process :as process]
            [oscope.config :as config]
            [oscope.config-cli :as config-cli]
            [oscope.managed-config-posix :as posix]
            [oscope.managed-config-store :as store]
            [oscope.managed-config-load-worker]
            [oscope.managed-config-worker])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def no-file-attributes (make-array FileAttribute 0))
(def no-link-options (make-array LinkOption 0))

(defn- delete-tree! [path]
  (when path
    (let [file (.toFile ^Path path)]
      (when (and (.exists file) (.isDirectory file)
                 (not (Files/isSymbolicLink path)))
        (doseq [child (.listFiles file)]
          (delete-tree! (.toPath child))))
      (.delete file))))

(defn- candidate [root present?]
  (store/managed-store
   [] {"XDG_CONFIG_HOME" (str root)}
   {"os.name" "Linux" "os.arch" "amd64" "user.home" "/ignored"}
   present? (posix/operations store/max-config-bytes)))

(defn- document [port]
  (assoc-in config/defaults [:server :port] port))

(defn- marker! [path]
  (Files/write path (.getBytes "go" "UTF-8")
               (make-array java.nio.file.OpenOption 0)))

(defn- wait-for-paths! [paths]
  (let [deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (cond
        (every? #(Files/exists % no-link-options) paths) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

(defn- worker [root revision port ready start result]
  (process/process
   [(or (System/getenv "JOLT_BIN") "jolt")
    "-M:test-managed-config-worker"
    (str root) revision (str port) (str ready) (str start) (str result)]
   {:out :string :err :string :dir (System/getProperty "user.dir")}))

(defn- load-worker [root port result]
  (process/process
   [(or (System/getenv "JOLT_BIN") "jolt")
    "-M:test-managed-config-load-worker"
    (str root) (str port) (str result)]
   {:out :string :err :string :dir (System/getProperty "user.dir")}))

(defn- call-private [name & arguments]
  (apply (deref (ns-resolve 'oscope.managed-config-posix name)) arguments))

(deftest linux-posix-store-is-private-atomic-and-restart-readable
  (let [root (Files/createTempDirectory "oscope-managed-posix-"
                                        no-file-attributes)
        target (.resolve root "oscope/config.edn")]
    (try
      (let [environment {"XDG_CONFIG_HOME" (str root)}
            first-store (store/managed-store [] environment)
            absent (store/snapshot! first-store)
            saved (store/replace! first-store (:revision absent)
                                  (document 14318))
            restarted (store/managed-store [] environment)
            rediscovered (store/snapshot! restarted)]
        (is (= :ok (:status saved)))
        (is (= (:revision saved) (:revision rediscovered)))
        (is (= :managed-user (store/selection-origin restarted)))
        (is (= (document 14318)
               (-> (String. (Files/readAllBytes target) "UTF-8")
                   config/parse config/file-document config/validate)))
        (is (= "rwx------"
               (PosixFilePermissions/toString
                (Files/getPosixFilePermissions (.getParent target)
                                               no-link-options))))
        (is (= "rw-------"
               (PosixFilePermissions/toString
                (Files/getPosixFilePermissions target no-link-options))))
        (is (empty? (filter #(.contains (.getName %) ".tmp")
                            (seq (.listFiles (.toFile (.getParent target))))))))
      (finally (delete-tree! root)))))

(deftest linux-posix-store-rejects-linked-directory-and-target
  (let [root (Files/createTempDirectory "oscope-managed-links-"
                                        no-file-attributes)
        outside (.resolve root "outside")
        app (.resolve root "oscope")
        target (.resolve app "config.edn")]
    (try
      (Files/createDirectory outside no-file-attributes)
      (Files/setPosixFilePermissions
       outside (PosixFilePermissions/fromString "rwx------"))
      (Files/createSymbolicLink app outside no-file-attributes)
      (is (= :unsafe-directory
             (:reason (store/snapshot! (candidate root (constantly true))))))
      (Files/delete app)
      (Files/createDirectory app no-file-attributes)
      (Files/setPosixFilePermissions app
                                     (PosixFilePermissions/fromString "rwx------"))
      (let [outside-config (.resolve outside "config.edn")]
        (Files/write outside-config (.getBytes (config/encode (document 14318))
                                                "UTF-8")
                     (make-array java.nio.file.OpenOption 0))
        (Files/setPosixFilePermissions
         outside-config (PosixFilePermissions/fromString "rw-------"))
        (Files/createSymbolicLink target outside-config
                                no-file-attributes)
        (is (= :unsafe-target
               (:reason (store/snapshot! (candidate root (constantly true)))))))
      (finally (delete-tree! root)))))

(deftest linux-posix-store-rejects-a-nonprivate-existing-target
  (let [root (Files/createTempDirectory "oscope-managed-mode-"
                                        no-file-attributes)
        target (.resolve root "oscope/config.edn")]
    (try
      (let [managed (candidate root (constantly false))
            absent (store/snapshot! managed)]
        (is (= :ok (:status
                   (store/replace! managed (:revision absent) (document 14318)))))
        (Files/setPosixFilePermissions target
                                       (PosixFilePermissions/fromString "rw-r--r--"))
        (is (= :permission-unverified
               (:reason (store/snapshot! (candidate root (constantly true)))))))
      (finally (delete-tree! root)))))

(deftest linux-posix-store-rejects-a-linked-lock-file
  (let [root (Files/createTempDirectory "oscope-managed-lock-link-"
                                        no-file-attributes)
        app (.resolve root "oscope")
        outside-lock (.resolve root "outside-lock")]
    (try
      (Files/createDirectory app no-file-attributes)
      (Files/setPosixFilePermissions app
                                     (PosixFilePermissions/fromString "rwx------"))
      (Files/write outside-lock (.getBytes "untouched" "UTF-8")
                   (make-array java.nio.file.OpenOption 0))
      (Files/setPosixFilePermissions outside-lock
                                     (PosixFilePermissions/fromString "rw-------"))
      (Files/createSymbolicLink (.resolve app ".oscope-config.lock") outside-lock
                                no-file-attributes)
      (let [managed (candidate root (constantly false))
            result (store/replace! managed store/absent-revision
                                   (document 14318))]
        (is (= :unsafe-target (:reason result)))
        (is (= "untouched" (String. (Files/readAllBytes outside-lock) "UTF-8"))))
      (finally (delete-tree! root)))))

(deftest linux-posix-flock-serializes-cross-process-cas
  (let [root (Files/createTempDirectory "oscope-managed-cas-"
                                        no-file-attributes)
        start (.resolve root "start")
        ready-a (.resolve root "ready-a")
        ready-b (.resolve root "ready-b")
        result-a (.resolve root "result-a")
        result-b (.resolve root "result-b")
        children (atom [])]
    (try
      (let [managed (candidate root (constantly false))
            absent (:revision (store/snapshot! managed))
            initial (store/replace! managed absent (document 14318))
            revision (:revision initial)
            child-a (worker root revision 14319 ready-a start result-a)
            child-b (worker root revision 14320 ready-b start result-b)]
        (reset! children [child-a child-b])
        (is (wait-for-paths! [ready-a ready-b]))
        (marker! start)
        (let [process-a (deref child-a 30000 ::timeout)
              process-b (deref child-b 30000 ::timeout)]
          (is (not= ::timeout process-a))
          (is (not= ::timeout process-b))
          (when (map? process-a) (is (zero? (:exit process-a))))
          (when (map? process-b) (is (zero? (:exit process-b))))
          (is (wait-for-paths! [result-a result-b]))
          (is (= #{"ok" "conflict"}
                 #{(String. (Files/readAllBytes result-a) "UTF-8")
                   (String. (Files/readAllBytes result-b) "UTF-8")}))
          (let [final-port (-> (Files/readAllBytes (.resolve root
                                                            "oscope/config.edn"))
                               (String. "UTF-8") config/parse config/file-document
                               config/validate (get-in [:server :port]))]
            (is (contains? #{14319 14320} final-port)))))
      (finally
        (doseq [child @children]
          (try (process/destroy-tree child) (catch Throwable _ nil)))
        (delete-tree! root)))))

(deftest posix-zero-result-boundary-retries-eintr
  (let [calls (atom 0)]
    (is (nil? (call-private
               'retry-zero!
               (fn [] (if (= 1 (swap! calls inc)) [-1 4] [0 4]))
               :force-failed)))
    (is (= 2 @calls))))

(deftest native-rename-failure-never-predeletes-the-prior-config
  (let [root (Files/createTempDirectory "oscope-managed-rename-"
                                        no-file-attributes)
        target (.resolve root "oscope/config.edn")]
    (try
      (let [managed (candidate root (constantly false))
            absent (:revision (store/snapshot! managed))
            saved (store/replace! managed absent (document 14318))
            rename-var (ns-resolve 'oscope.managed-config-posix 'c-renameat)
            failed (with-redefs-fn {rename-var (fn [& _] [-1 5])}
                     #(store/replace! managed (:revision saved)
                                      (document 14319)))]
        (is (= :atomic-replace-failed (:reason failed)))
        (is (= (document 14318)
               (-> (Files/readAllBytes target) (String. "UTF-8")
                   config/parse config/file-document config/validate)))
        (is (empty? (filter #(.contains (.getName %) ".tmp")
                            (seq (.listFiles (.toFile (.getParent target))))))))
      (finally (delete-tree! root)))))

(deftest independent-restart-consumes-the-managed-file-through-normal-xdg-loading
  (let [root (Files/createTempDirectory "oscope-managed-restart-"
                                        no-file-attributes)
        result (.resolve root "load-result")
        child (atom nil)]
    (try
      (let [managed (store/managed-store
                     [] {"XDG_CONFIG_HOME" (str root)})
            absent (:revision (store/snapshot! managed))]
        (is (= :ok (:status
                   (store/replace! managed absent (document 14321)))))
        (reset! child (load-worker root 14321 result))
        (let [process-result (deref @child 30000 ::timeout)]
          (is (not= ::timeout process-result))
          (when (map? process-result)
            (is (zero? (:exit process-result))))
          (is (wait-for-paths! [result]))
          (is (= "ok" (String. (Files/readAllBytes result) "UTF-8")))))
      (finally
        (when @child
          (try (process/destroy-tree @child) (catch Throwable _ nil)))
        (delete-tree! root)))))
