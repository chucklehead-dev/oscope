(ns oscope.embedded-profile-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.process :as process]
            [oscope.child-support :as child]))

(def ^:private embedded-profile-dir "profiles/embedded")
(def ^:private minimal-fixture-dir "test/fixtures/minimal-embedded-app")
(def ^:private minimal-fixture-source
  (str minimal-fixture-dir "/src/minimal_embedded_app.clj"))
(def ^:private samizdat-converged-fixture-dir
  "test/fixtures/samizdat-converged-db-graph")
(def ^:private samizdat-pre-convergence-fixture-dir
  "test/fixtures/samizdat-pre-convergence-db-graph")

(def ^:private expected-profile-deps
  '#{io.github.casselc/otel
     io.github.chucklehead-dev/jolt-chdb
     io.github.chucklehead-dev/jolt-otel-clickhouse
     metosin/malli
     org.clojure/data.json})

(def ^:private coordinates
  {:otel [["casselc_otel.git" "io.github.casselc/otel"]
          ["https___github.com_casselc_otel.git/4d61f8e921d1310bc7ba39d7208cc38ac14a3215/"
           "io.github.casselc/otel/4d61f8e921d1310bc7ba39d7208cc38ac14a3215/"]]
   :chdb [["chucklehead-dev_jolt-chdb.git"
           "io.github.chucklehead-dev/jolt-chdb"]
          ["https___github.com_chucklehead-dev_jolt-chdb.git/95d7b2b31c95e007d5065e3950deb1869e2d0f8a/"
           "io.github.chucklehead-dev/jolt-chdb/95d7b2b31c95e007d5065e3950deb1869e2d0f8a/"]]
   :historical-chdb [["chucklehead-dev_jolt-chdb.git"
                      "io.github.chucklehead-dev/jolt-chdb"]
                     ["https___github.com_chucklehead-dev_jolt-chdb.git/dbc2db22130c7e783739c79bc24691dcbba21906/"
                      "io.github.chucklehead-dev/jolt-chdb/dbc2db22130c7e783739c79bc24691dcbba21906/"]]
   :clickhouse [["jolt-otel-clickhouse"
                 "io.github.chucklehead-dev/jolt-otel-clickhouse"]
                ["https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/14a2998a27f64a9bff329811461be9157a00c849/"
                 "io.github.chucklehead-dev/jolt-otel-clickhouse/14a2998a27f64a9bff329811461be9157a00c849/"]]
   :data-json [["casselc_data.json.git" "org.clojure/data.json"]
               ["https___github.com_casselc_data.json.git/3174868a7baa06e118fb8d1201edd98c5769b335/"
                "org.clojure/data.json/3174868a7baa06e118fb8d1201edd98c5769b335/"]]
   :reviewed-db [["casselc_db.git" "jolt-lang/db"]
                 ["https___github.com_casselc_db.git/6db791634e5a4c65c24646833b2e82d3a5d7a121/"
                  "jolt-lang/db/6db791634e5a4c65c24646833b2e82d3a5d7a121/"]]
   :converged-db [["casselc_db.git" "jolt-lang/db"]
                  ["https___github.com_casselc_db.git/96324713500c96ae97c0deaf84691f31df158f25/"
                   "jolt-lang/db/96324713500c96ae97c0deaf84691f31df158f25/"]]
   :historical-casselc-db
   [["casselc_db.git" "io.github.casselc/db"]
    ["https___github.com_casselc_db.git/a5bf25d9e141e8dcf28d6d07cd053fceb0019563/"
     "io.github.casselc/db/a5bf25d9e141e8dcf28d6d07cd053fceb0019563/"]]
   :historical-samizdat-db
   [["jolt-lang_db" "jolt-lang/db"]
    ["https___github.com_jolt-lang_db/d85f391ca521da389b935c38f3d78b30eaa23208/"
     "jolt-lang/db/d85f391ca521da389b935c38f3d78b30eaa23208/"]]
   :casselc-http [["casselc_http-client.git"
                   "io.github.casselc/http-client"]
                  ["https___github.com_casselc_http-client.git/eab6b78d5957f88690faf6768360572a3f185341/"
                   "io.github.casselc/http-client/eab6b78d5957f88690faf6768360572a3f185341/"]]
   :samizdat-http [["jolt-lang_http-client" "jolt-lang/http-client"]
                   ["https___github.com_jolt-lang_http-client/ccce992d6e3d0035a5ffd1d4364cdb39df4af2f0/"
                    "jolt-lang/http-client/ccce992d6e3d0035a5ffd1d4364cdb39df4af2f0/"]]})

(def ^:private reviewed-db-sha
  "6db791634e5a4c65c24646833b2e82d3a5d7a121")
(def ^:private converged-db-sha
  "96324713500c96ae97c0deaf84691f31df158f25")

(defn- run-jolt [dir & args]
  (child/run! (into [(or (System/getenv "JOLT_BIN") "jolt") "-Srepro"] args)
              {:dir dir :out :string :err :string} {}))

(defn- delete-tree! [root]
  (when (and root (.exists root))
    (doseq [file (reverse (file-seq root))]
      (java.nio.file.Files/deleteIfExists (.toPath file)))))

(deftest owned-child-settlement-controls-cross-the-shared-helper
  (doseq [scenario [:success :nonzero :timeout :wait-threw :unconfirmed
                    :termination-threw :cleanup-threw :nonzero-cleanup-threw]]
    (let [events (atom [])
          waits (atom 0)
          owned (Object.)
          original-deref clojure.core/deref
          result {:exit (if (contains? #{:nonzero :nonzero-cleanup-threw} scenario) 7 0)
                  :out "fixture" :err ""}]
      (with-redefs [process/process (fn [& _] (swap! events conj :spawn) owned)
                    process/destroy-tree
                    (fn [actual]
                      (is (identical? owned actual))
                      (swap! events conj :terminate)
                      (when (= :termination-threw scenario)
                        (throw (ex-info "private-termination-marker" {}))))
                    clojure.core/deref
                    (fn [& args]
                      (if (identical? owned (first args))
                        (let [ordinal (swap! waits inc)]
                          (swap! events conj [:wait ordinal])
                          (cond
                            (and (= :wait-threw scenario) (= 1 ordinal))
                            (throw (ex-info "private-wait-marker" {}))
                            (or (= :unconfirmed scenario)
                                (and (= 1 ordinal)
                                     (contains? #{:timeout :termination-threw} scenario)))
                            (nth args 2)
                            :else result))
                        (apply original-deref args)))]
        (let [actual (child/run! ["fixture"] {}
                                {:timeout-ms 1 :settlement-ms 1
                                 :after-terminal!
                                 #(do (swap! events conj :delete)
                                      (when (contains? #{:cleanup-threw :nonzero-cleanup-threw}
                                                       scenario)
                                        (throw (ex-info "private-cleanup-marker" {}))))})
              timed? (contains? #{:timeout :wait-threw :unconfirmed :termination-threw}
                               scenario)]
          (is (= (if timed? 2 1) @waits))
          (is (= (cond
                   (= :unconfirmed scenario) [:spawn [:wait 1] :terminate [:wait 2]]
                   timed? [:spawn [:wait 1] :terminate [:wait 2] :delete]
                   :else [:spawn [:wait 1] :delete]) @events))
          (if (contains? #{:success :nonzero} scenario)
            (is (= result actual))
            (do
              (is (= 1 (:exit actual)))
              (is (= (not= :unconfirmed scenario) (:child/terminal? actual)))
              (is (= (case scenario
                       :wait-threw :wait-threw
                       :cleanup-threw :none
                       :nonzero-cleanup-threw :nonzero-exit
                       :timeout) (:child/primary actual)))
              (is (= (case scenario
                       :unconfirmed :preserved
                       :cleanup-threw :cleanup-threw
                       :nonzero-cleanup-threw :cleanup-threw
                       :completed) (:child/cleanup actual)))
              (is (not (str/includes? (pr-str actual) "private-"))))))))))

(deftest owned-child-real-timeout-confirms-terminal-before-cleanup
  ;; A direct tiny child, no shell or descendants; timeout stays a failure even
  ;; after termination confirms exit. No native fixture is needed for this cut.
  (let [cleanups (atom 0)
        result (child/run! ["/bin/sleep" "5"] {:out :string :err :string}
                           {:timeout-ms 20 :settlement-ms 5000
                            :after-terminal! #(swap! cleanups inc)})]
    (is (= 1 (:exit result)))
    (is (= :timeout (:child/primary result)))
    (is (true? (:child/terminal? result)))
    (is (= 1 @cleanups))
    (is (= :completed (:child/cleanup result)))))

(defn- classpath-roots [classpath fragments]
  (let [fragments (if (string? fragments) [fragments] fragments)]
    (->> (str/split (str classpath) #":")
         (filter (fn [path]
                   (some #(str/includes? path %) fragments)))
         vec)))

(defn- exact-coordinate? [classpath [dependency-fragments expected-roots]]
  (let [paths (classpath-roots classpath dependency-fragments)
        expected-roots (if (string? expected-roots)
                         [expected-roots]
                         expected-roots)]
    (and (seq paths)
         (every? (fn [path]
                   (some #(str/includes? path %) expected-roots))
                 paths))))

(defn- run-command [dir args]
  (child/run! args {:dir dir :out :string :err :string} {:timeout-ms 10000}))

(defn- namespace-providers [classpath source-path]
  (->> (str/split (str classpath) #":")
       (filter #(.isFile (java.io.File. % source-path)))
       vec))

(defn- database-provider-roots [classpath]
  (->> (str/split (str classpath) #":")
       (filter #(.isDirectory (java.io.File. % "db")))
       vec))

(deftest embedded-profile-is-minimal-and-pinned
  (let [profile (edn/read-string
                 (slurp (str embedded-profile-dir "/deps.edn")))]
    (is (= "0.8.6" (:jolt/min-version profile)))
    (is (= ["../../src"] (:paths profile)))
    (is (= expected-profile-deps (set (keys (:deps profile)))))
    (is (= "0.20.1" (get-in profile [:deps 'metosin/malli :mvn/version])))
    (is (nil? (get-in profile [:deps 'io.github.casselc/jolt-http])))
    (is (nil? (get-in profile
                       [:deps 'io.github.chucklehead-dev/jolt-otel-viewer])))))

(deftest minimal-fixture-source-and-dependencies-are-converged
  (let [source (slurp minimal-fixture-source)]
    (is (not (str/includes? source "with-redefs"))
        "core acceptance must not replace native/JDBC/exporter behavior")
    (doseq [required ["sqlite:"
                      "local-posix/local-backend"
                      "durable/writer-dbspec"
                      "manifest/compile-manifest"
                      "embedded/start!"
                      "trace/with-span"
                      "http/get"
                      "embedded/status"
                      "embedded-query/start!"]]
      (is (str/includes? source required)
          (str "minimal native fixture lost required behavior: " required))))
  (let [classpath-result (run-jolt minimal-fixture-dir "-Spath")]
    (is (map? classpath-result))
    (when (map? classpath-result)
      (is (zero? (:exit classpath-result)))
      (let [classpath (:out classpath-result)]
        (doseq [coordinate (map coordinates [:otel :chdb :clickhouse :data-json])]
          (is (exact-coordinate? classpath coordinate)))
        (is (exact-coordinate? classpath (:converged-db coordinates)))
        (is (= 1 (count (database-provider-roots classpath))))
        (is (exact-coordinate? classpath (:casselc-http coordinates)))
        (is (= 1 (count (namespace-providers classpath "db/sqlite.clj"))))
        (is (= 1 (count (namespace-providers classpath
                                             "jolt/http_client.clj"))))
        (is (empty? (classpath-roots classpath "casselc_jolt-http")))
        (is (empty? (classpath-roots classpath "jolt-otel-viewer")))))))

(deftest minimal-fixture-runs-the-real-native-stalled-remote-stack
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "oscope-minimal-embedded-parent-"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (let [run-result
          (child/run!
           [(or (System/getenv "JOLT_BIN") "jolt") "-Srepro" "-M:test" (str root)]
           {:dir minimal-fixture-dir :out :string :err :string}
           {:after-terminal! #(delete-tree! root)})]
      (is (map? run-result))
      (when (map? run-result)
        (is (zero? (:exit run-result))
            (str (:out run-result) (:err run-result)))
        (is (str/includes? (:out run-result)
                           "minimal embedded native fixture: PASS"))))))

(deftest converged-database-coordinate-qualifies-one-provider
  (let [result (run-jolt samizdat-converged-fixture-dir "-Spath")]
    (is (map? result))
    (when (map? result)
      (is (zero? (:exit result)))
      (let [classpath (:out result)
            db-root (first (namespace-providers classpath "db/sqlite.clj"))
            resolved-head (run-command db-root ["git" "rev-parse" "HEAD"])
            ancestry (run-command db-root
                                  ["git" "merge-base" "--is-ancestor"
                                   reviewed-db-sha converged-db-sha])]
        (testing "authoritative SQLite and Durable share one database provider"
          (is (exact-coordinate? classpath (:converged-db coordinates)))
          (is (= 1 (count (database-provider-roots classpath))))
          (is (= 1 (count (namespace-providers classpath "db/sqlite.clj"))))
          (is (= 1 (count (namespace-providers classpath
                                               "jdbc/chdb/durable.clj")))))
        (testing "resolved git metadata proves the reviewed provider ancestry"
          (is (map? resolved-head))
          (is (map? ancestry))
          (when (and (map? resolved-head) (map? ancestry))
            (is (= converged-db-sha (str/trim (:out resolved-head))))
            (is (zero? (:exit ancestry)))))
        (testing "Samizdat HTTP migration remains outside this qualification"
          (is (exact-coordinate? classpath (:casselc-http coordinates)))
          (is (exact-coordinate? classpath (:samizdat-http coordinates)))
          ;; Intentional tripwire: this remains red until Samizdat migrates its
          ;; HTTP coordinate; it does not invalidate the DB-only qualification.
          (is (= 2 (count (namespace-providers classpath
                                               "jolt/http_client.clj")))))))))

(deftest pre-convergence-database-coordinates-are-a-causal-red-control
  (let [result (run-jolt samizdat-pre-convergence-fixture-dir "-Spath")]
    (is (map? result))
    (when (map? result)
      (is (zero? (:exit result)))
      (let [classpath (:out result)]
        (is (exact-coordinate? classpath (:historical-chdb coordinates)))
        (is (exact-coordinate? classpath
                               (:historical-casselc-db coordinates)))
        (is (exact-coordinate? classpath
                               (:historical-samizdat-db coordinates)))
        (is (= 2 (count (database-provider-roots classpath))))
        (is (= 2 (count (namespace-providers classpath "db/sqlite.clj"))))))))
