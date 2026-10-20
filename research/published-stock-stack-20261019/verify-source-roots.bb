(require '[clojure.string :as str])

(let [[before after] *command-line-args*
      roots #(str/split (str/trim (slurp %)) #":")
      old (roots before)
      new (roots after)
      crypto (filter #(str/includes? % "jolt-crypto") new)]
  (assert (seq old))
  (assert (= (frequencies old) (frequencies new)))
  (assert (= 1 (count crypto)))
  (assert (str/ends-with? (first crypto)
            "/https___github.com_casselc_jolt-crypto.git/649c993780a0f269938c9c6afa3bae7e9709bb48/src"))
  (prn {:effective-source-root-multiset-unchanged true
        :crypto-provider-count (count crypto)
        :scope :saved-classpath-receipts-not-runtime-execution}))
