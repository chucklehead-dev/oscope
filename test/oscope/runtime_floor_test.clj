(ns oscope.runtime-floor-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private runtime-floor "0.8.6")
(def ^:private root-driver-sha
  "ed6efde5eac1bef76d4a588d41aa44dead7f47c3")
(def ^:private ordinary-driver-sha
  "3e3141fd29b335aa7bf2b6039fed7b3bbb4ebe04")
(def ^:private woven-qualification-driver-sha
  "3e3141fd29b335aa7bf2b6039fed7b3bbb4ebe04")
(def ^:private aspect-sha
  "d5f42a309ac52ccd8dfa54d256fa2fa502d1fd7c")
(def ^:private aspect-compiler-sha
  "f00bc93bdd8274b14087b74272aadeffb60e0447")

(defn- executable-declarations [workflow]
  (str/join "\n" (remove #(re-find #"^\s*#" %)
                         (str/split-lines workflow))))

(defn- minio-source-policy? [workflow builder]
  (and (boolean (re-find #"repository: minio/minio\s+ref: 07c3a429bfed433e49018cb0f78a52145d4bedeb\s+path: \.qualification/minio\s+persist-credentials: false"
                        (executable-declarations workflow)))
       (str/includes? workflow "go-version: '1.24.6'")
       (str/includes? workflow "run: bash test/build_minio_fixture.sh .qualification/minio")
       (not (str/includes? (executable-declarations workflow) "MINIO_IMAGE:"))
       (str/includes? builder "expected_source=07c3a429bfed433e49018cb0f78a52145d4bedeb")
       (str/includes? builder "= go1.24.6")
       (str/includes? builder "GOTOOLCHAIN=local CGO_ENABLED=0 GOMAXPROCS=2")
       (str/includes? builder "-mod=readonly")
       (str/includes? builder "vcs.modified=false")
       (str/includes? builder "--kill-after=5s 600s")))

(deftest minio-fixture-source-and-toolchain-are-independent-pins
  (let [workflow (slurp ".github/workflows/durable-s3-e2e.yml")
        builder (slurp "test/build_minio_fixture.sh")]
    (is (minio-source-policy? workflow builder))
    (is (not (minio-source-policy?
              (str/replace workflow "07c3a429bfed433e49018cb0f78a52145d4bedeb" "main")
              builder)))
    (is (not (minio-source-policy? workflow
              (str/replace builder "-mod=readonly" "-mod=mod"))))
    (is (not (minio-source-policy?
              (str/replace workflow "go-version: '1.24.6'" "go-version: 'stable'")
              builder)))
    (is (not (minio-source-policy? (str workflow "\nMINIO_IMAGE: minio/minio:latest\n")
                                 builder)))))

(defn- workflow-pin [workflow name]
  (let [prefix (str name ":")
        values (->> (str/split-lines workflow)
                    (map str/trim)
                    (filter #(str/starts-with? % prefix))
                    (map #(str/trim (subs % (count prefix))))
                    (map (fn [value]
                           (let [length (count value)]
                             (if (and (>= length 2)
                                      (or (and (str/starts-with? value "\"")
                                               (str/ends-with? value "\""))
                                          (and (str/starts-with? value "'")
                                               (str/ends-with? value "'"))))
                               (subs value 1 (dec length))
                               value))))
                    vec)]
    ;; Artifact declarations are unique: reject even duplicate identical pins.
    ;; Woven executable selections legitimately repeat across several steps.
    (when (and (seq values) (every? seq values) (apply = values)
               (or (not (str/starts-with? name "QUALIFIED_RUNTIME_"))
                   (= 1 (count values))))
      (first values))))

(defn- embedded-compiler-selection? [workflow]
  ;; Independently checked against chDB 3e3141f's immutable installer, not
  ;; inferred from this workflow's own declarations or the local executable.
  (and (= "57e591d4d6481c5536858575ffc006cf5a41adbf"
          (workflow-pin workflow "PINNED_JOLT_SOURCE_SHA"))
       (= "jolt v0.8.6-37-g57e591d4"
          (workflow-pin workflow "PINNED_JOLT_VERSION"))))

(deftest embedded-compiler-selection-matches-the-qualified-installer
  (let [workflow (slurp ".github/workflows/embedded-native-profile.yml")]
    (is (embedded-compiler-selection? workflow))
    (is (not (embedded-compiler-selection?
              (str/replace workflow
                           "57e591d4d6481c5536858575ffc006cf5a41adbf"
                           "bf8a5dde7bebb5658d218e9757ab1df0aa9c3b95"))))
    (is (not (embedded-compiler-selection?
              (str/replace workflow "jolt v0.8.6-37-g57e591d4"
                           "jolt v0.8.6-599-gbf8a5dde"))))))

(def ^:private runtime-pin-names
  ["QUALIFIED_RUNTIME_RUN_ID" "QUALIFIED_RUNTIME_RUN_ATTEMPT"
   "QUALIFIED_RUNTIME_ARTIFACT_ID" "QUALIFIED_RUNTIME_WORKFLOW_SHA"
   "QUALIFIED_RUNTIME_ARTIFACT_SHA256" "QUALIFIED_RUNTIME_BINARY_SHA256"])

(def ^:private authenticated-runtime-release
  {:pins ["35188849252" "1" "10483062488"
          "6bf745bf6101f2c3481d0ffe16401843c7675050"
          "353670dda1447d96d46aa1e133e4910481caf2f328d159a9bde76ed863e41524"
          "31cff7ea89a652bd99848cf15686eb576d90d9ac3962ac07701fb6005e6eb458"]
   :helper-source "https://raw.githubusercontent.com/chucklehead-dev/jolt-otel-clickhouse/c26c9bfcf071112531974729b15469b8c9fe713a/scripts/fetch-qualified-durable-runtime.sh"
   :helper-sha "e962976aea263f00440fdf9bfa53c36a158c501f2b87d15aa8a84867fe51caae"})

(def ^:private caller-independent-runtime-release
  (assoc authenticated-runtime-release
         :helper-source "https://raw.githubusercontent.com/chucklehead-dev/jolt-otel-clickhouse/3c683bee4f1a0d5ed91d691cb14330e30d2fcdeb/scripts/fetch-qualified-durable-runtime.sh"
         :helper-sha "5861a525e3bf755b38966550476f172c87d6678e117c2e07b0d5777b1370fbc2"))

(def ^:private native-workflow-releases
  ;; Helper rollout is workflow-specific; the authenticated artifact is unchanged.
  [[".github/workflows/durable-s3-e2e.yml" true caller-independent-runtime-release]
   [".github/workflows/durable-aws.yml" false authenticated-runtime-release]
   [".github/workflows/langfuse-interop.yml" false authenticated-runtime-release]])

(defn- declarations-match-release? [workflow expected]
  (let [workflow (executable-declarations workflow)]
    (and (= (:pins expected) (mapv #(workflow-pin workflow %) runtime-pin-names))
         (= (:helper-source expected)
            (workflow-pin workflow "QUALIFIED_RUNTIME_HELPER_SOURCE"))
         (= (:helper-sha expected)
            (workflow-pin workflow "QUALIFIED_RUNTIME_HELPER_SHA256")))))

(defn- immutable-pin-shape? [value pattern]
  (and (string? value) (boolean (re-matches pattern value))
       (not (every? #(= \0 %) value))))

(defn- native-provider-contract [workflow woven?]
  ;; This checks declared source/provider concordance BEFORE running native
  ;; effects. Artifact provenance and actual capability still need real gates.
  (let [workflow (executable-declarations workflow)
        pins (mapv #(workflow-pin workflow %) runtime-pin-names)
        helper-source (second
                       (re-matches
                        #"https://raw.githubusercontent.com/chucklehead-dev/jolt-otel-clickhouse/([a-f0-9]{40})/scripts/fetch-qualified-durable-runtime.sh"
                        (or (workflow-pin workflow "QUALIFIED_RUNTIME_HELPER_SOURCE") "")))]
    {:native-source (boolean
                     (re-find (re-pattern
                               (str "repository: chucklehead-dev/jolt-chdb\\s+ref: "
                                    ordinary-driver-sha "(?:\\s|$)")) workflow))
     :artifact-pins (and (every? #(immutable-pin-shape? % #"[1-9][0-9]*")
                                (take 3 pins))
                         (immutable-pin-shape? (nth pins 3) #"[a-f0-9]{40}")
                         (every? #(immutable-pin-shape? % #"[a-f0-9]{64}")
                                 (drop 4 pins)))
     :trusted-identity (and (= "casselc/jolt"
                              (workflow-pin workflow "QUALIFIED_RUNTIME_REPOSITORY"))
                            (= "aea91781bbab68bf174fef4a689bb00dcf834ded"
                               (workflow-pin workflow "QUALIFIED_RUNTIME_COMPILER_SOURCE"))
                            (= "a31de1596fcabd0e45fbcbc528842805acea0ee7"
                               (workflow-pin workflow "QUALIFIED_RUNTIME_COMPILER_TREE")))
     :helper-attestation
     (and (immutable-pin-shape? helper-source #"[a-f0-9]{40}")
          (immutable-pin-shape? (workflow-pin workflow "QUALIFIED_RUNTIME_HELPER_SHA256")
                               #"[a-f0-9]{64}")
          (str/includes? workflow "sha256sum -c"))
     :qualified-positive-path
     (and (str/includes? workflow
                         "echo \"JOLT_BIN=$QUALIFIED_RUNTIME_BIN\" >> \"$GITHUB_ENV\"")
          (str/includes? workflow
                         "dirname \"$QUALIFIED_RUNTIME_BIN\" >> \"$GITHUB_PATH\"")
          (not (re-find #"(?m)^\s*JOLT_BIN:\s*jolt\s*$" workflow)))
     :woven-preserved (or (not woven?)
                         (and (boolean
                               (re-find (re-pattern
                                         (str "repository: casselc/jolt\\s+ref: "
                                              aspect-compiler-sha "(?:\\s|$)")) workflow))
                              (= "${{ github.workspace }}/.qualification/jolt/bin/jolt"
                                 (workflow-pin workflow "JOLT_ASPECT_JOLT"))))}))

(defn- synthetic-qualified-declaration-fixture []
  ;; SYNTHETIC DECLARATION FIXTURE ONLY: these invented pins are grammar
  ;; controls, never real artifact provenance or a candidate release snapshot.
  (str "repository: chucklehead-dev/jolt-chdb\nref: " ordinary-driver-sha "\n"
       "QUALIFIED_RUNTIME_REPOSITORY: casselc/jolt\n"
       "QUALIFIED_RUNTIME_COMPILER_SOURCE: aea91781bbab68bf174fef4a689bb00dcf834ded\n"
       "QUALIFIED_RUNTIME_COMPILER_TREE: a31de1596fcabd0e45fbcbc528842805acea0ee7\n"
       "QUALIFIED_RUNTIME_RUN_ID: 1\nQUALIFIED_RUNTIME_RUN_ATTEMPT: 1\n"
       "QUALIFIED_RUNTIME_ARTIFACT_ID: 1\n"
       "QUALIFIED_RUNTIME_WORKFLOW_SHA: " (apply str (repeat 40 "a")) "\n"
       "QUALIFIED_RUNTIME_ARTIFACT_SHA256: " (apply str (repeat 64 "b")) "\n"
       "QUALIFIED_RUNTIME_BINARY_SHA256: " (apply str (repeat 64 "c")) "\n"
       "QUALIFIED_RUNTIME_HELPER_SHA256: " (apply str (repeat 64 "d")) "\n"
       "QUALIFIED_RUNTIME_HELPER_SOURCE: https://raw.githubusercontent.com/chucklehead-dev/jolt-otel-clickhouse/"
       (apply str (repeat 40 "e")) "/scripts/fetch-qualified-durable-runtime.sh\n"
       "printf checksum | sha256sum -c -\n"
       "echo \"JOLT_BIN=$QUALIFIED_RUNTIME_BIN\" >> \"$GITHUB_ENV\"\n"
       "dirname \"$QUALIFIED_RUNTIME_BIN\" >> \"$GITHUB_PATH\"\n"
       "repository: casselc/jolt\nref: " aspect-compiler-sha "\n"
       "JOLT_ASPECT_JOLT: ${{ github.workspace }}/.qualification/jolt/bin/jolt\n"))

(deftest provider-declaration-controls-reject-single-boundary-mutations
  (is (= "one" (workflow-pin "FIELD: one\n" "FIELD")))
  (is (= "one" (workflow-pin " FIELD: 'one'\n" "FIELD")))
  (is (= "one" (workflow-pin "FIELD: \"one\"\n" "FIELD")))
  (is (nil? (workflow-pin "FIELD_SUFFIX: one\n" "FIELD")))
  (is (nil? (workflow-pin "FIELD: \n" "FIELD")))
  (is (nil? (workflow-pin "FIELD: ''\n" "FIELD")))
  (is (= "'one\"" (workflow-pin "FIELD: 'one\"\n" "FIELD")))
  (is (= "one'" (workflow-pin "FIELD: one'\n" "FIELD")))
  (let [fixture (synthetic-qualified-declaration-fixture)]
    (is (every? true? (vals (native-provider-contract fixture true))))
    (doseq [[boundary old replacement]
            [[:native-source ordinary-driver-sha "dbc2db22130c7e783739c79bc24691dcbba21906"]
             [:artifact-pins "QUALIFIED_RUNTIME_RUN_ID: 1" "QUALIFIED_RUNTIME_RUN_ID: pending"]
             [:artifact-pins "QUALIFIED_RUNTIME_BINARY_SHA256:" "MISSING_BINARY_PIN:"]
             [:trusted-identity "QUALIFIED_RUNTIME_REPOSITORY: casselc/jolt"
              "QUALIFIED_RUNTIME_REPOSITORY: other/jolt"]
             [:trusted-identity "QUALIFIED_RUNTIME_COMPILER_TREE:" "COMMENT_TREE:"]
             [:trusted-identity "QUALIFIED_RUNTIME_COMPILER_SOURCE:" "COMMENT_SOURCE:"]
             [:helper-attestation "/scripts/fetch-qualified-durable-runtime.sh"
              "/scripts/other.sh"]
             [:helper-attestation "QUALIFIED_RUNTIME_HELPER_SHA256:" "MISSING_HELPER_HASH:"]
             [:helper-attestation (apply str (repeat 64 "d")) "not-a-checksum"]
             [:artifact-pins (apply str (repeat 64 "c")) (apply str (repeat 64 "0"))]
             [:qualified-positive-path "JOLT_BIN=$QUALIFIED_RUNTIME_BIN" "JOLT_BIN=jolt"]
             [:qualified-positive-path "dirname \"$QUALIFIED_RUNTIME_BIN\"" "dirname jolt"]
             [:woven-preserved aspect-compiler-sha ordinary-driver-sha]]]
      (is (not (true? (get (native-provider-contract
                           (str/replace fixture old replacement) true) boundary)))
          (name boundary)))
    (is (not (true? (:qualified-positive-path
                    (native-provider-contract (str fixture "JOLT_BIN: jolt\n") true)))))
    (is (not (true? (:artifact-pins
                    (native-provider-contract
                     (str fixture "QUALIFIED_RUNTIME_RUN_ID: 1\n") true)))))
    (let [synthetic-release {:pins ["1" "1" "1" (apply str (repeat 40 "a"))
                                    (apply str (repeat 64 "b"))
                                    (apply str (repeat 64 "c"))]
                             :helper-source (workflow-pin fixture "QUALIFIED_RUNTIME_HELPER_SOURCE")
                             :helper-sha (apply str (repeat 64 "d"))}]
      (is (declarations-match-release? fixture synthetic-release))
      (doseq [[old replacement]
              [["QUALIFIED_RUNTIME_RUN_ID: 1" "QUALIFIED_RUNTIME_RUN_ID: 2"]
               [(apply str (repeat 40 "a")) (apply str (repeat 40 "f"))]
               [(apply str (repeat 64 "b")) (apply str (repeat 64 "f"))]
               [(apply str (repeat 64 "c")) (apply str (repeat 64 "f"))]
               [(apply str (repeat 40 "e")) (apply str (repeat 40 "f"))]
               [(apply str (repeat 64 "d")) (apply str (repeat 64 "f"))]]]
        (is (not (declarations-match-release?
                  (str/replace fixture old replacement) synthetic-release))))
      (is (not (declarations-match-release?
                (str fixture "QUALIFIED_RUNTIME_RUN_ID: 1\n") synthetic-release))))
    (is (not-any? true?
                  (vals (native-provider-contract
                         (str/join "\n" (map #(str "# " %)
                                               (str/split-lines fixture))) true))))))

(deftest native-integration-providers-declare-one-qualified-positive-graph
  (doseq [[path woven? expected] native-workflow-releases]
    (testing path
      (let [workflow (slurp path)]
        (is (declarations-match-release? workflow expected)
            "exact authenticated tuple and workflow-specific published helper")
        (doseq [[boundary confirmed?] (native-provider-contract workflow woven?)]
          (is (true? confirmed?) (name boundary)))))))

(deftest native-workflow-helper-mutations-remain-fail-closed
  (doseq [[path _ expected] native-workflow-releases]
    (testing path
      (let [workflow (slurp path)
            other (if (= expected caller-independent-runtime-release)
                    authenticated-runtime-release
                    caller-independent-runtime-release)]
        ;; Both helpers are valid immutable releases, but not interchangeable.
        (doseq [key [:helper-source :helper-sha]]
          (is (not (declarations-match-release?
                    (str/replace workflow (get expected key) (get other key))
                    expected))
              (name key)))
        (is (not (declarations-match-release? workflow other))
            "another workflow's complete helper tuple is rejected")
        (doseq [[field key] [["QUALIFIED_RUNTIME_HELPER_SOURCE" :helper-source]
                             ["QUALIFIED_RUNTIME_HELPER_SHA256" :helper-sha]]]
          (is (not (declarations-match-release?
                    (str workflow "\n" field ": " (get expected key) "\n")
                    expected))
              "duplicate identical helper declarations are rejected"))))))

(defn- release-workflow-at-floor? [workflow]
  (and (str/includes?
        workflow
        (str "https://raw.githubusercontent.com/jolt-lang/jolt/v"
             runtime-floor "/install"))
       (str/includes? workflow (str "--version " runtime-floor))
       (str/includes? workflow (str "\"jolt v" runtime-floor "\""))
       (not (str/includes? workflow "/jolt/v0.8.3/install"))
       (not (str/includes? workflow "--version 0.8.3"))
       (not (str/includes? workflow "\"jolt v0.8.3\""))))

(defn- qualification-at-floor? [deps workflows]
  (and (= runtime-floor (:jolt/min-version deps))
       (every? release-workflow-at-floor? workflows)))

(deftest source-documentation-and-hosted-runtimes-share-one-floor
  (let [deps (edn/read-string (slurp "deps.edn"))
        readme (slurp "README.md")
        tests-workflow (slurp ".github/workflows/tests.yml")
        s3-workflow (slurp ".github/workflows/durable-s3-e2e.yml")
        aws-workflow (slurp ".github/workflows/durable-aws.yml")]
    (is (qualification-at-floor?
         deps [tests-workflow s3-workflow aws-workflow]))
    (is (str/includes? readme "requires Jolt v0.8.6 or newer"))
    (doseq [workflow [tests-workflow s3-workflow aws-workflow]]
      (is (release-workflow-at-floor? workflow)))
    (is (str/includes? tests-workflow ".jolt-cache-v0.8.6"))
    (is (str/includes? tests-workflow ".jolt-gitlibs-v0.8.6"))
    (is (str/includes? s3-workflow ".jolt-cache-v0.8.6"))
    (is (str/includes? s3-workflow ".jolt-gitlibs-v0.8.6"))))

(deftest ordinary-and-woven-driver-revisions-remain-explicit
  (let [deps (edn/read-string (slurp "deps.edn"))
        s3-workflow (slurp ".github/workflows/durable-s3-e2e.yml")]
    (is (= root-driver-sha
           (get-in deps [:deps 'io.github.chucklehead-dev/jolt-chdb
                         :git/sha])))
    (is (str/includes? s3-workflow woven-qualification-driver-sha))
    (is (str/includes? s3-workflow aspect-sha))
    (is (str/includes? s3-workflow aspect-compiler-sha))))

(deftest downgrade-mutations-are-rejected-causally
  (let [deps (edn/read-string (slurp "deps.edn"))
        workflow (slurp ".github/workflows/tests.yml")]
    (testing "a declared 0.8.3 source floor fails the exact floor oracle"
      (is (false?
           (qualification-at-floor?
            (assoc deps :jolt/min-version "0.8.3") [workflow]))))
    (testing "a hosted 0.8.3 install fails the complete workflow oracle"
      (is (false?
           (release-workflow-at-floor?
            (str/replace workflow "0.8.6" "0.8.3")))))))
