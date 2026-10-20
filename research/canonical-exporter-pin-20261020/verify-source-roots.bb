(require '[clojure.string :as str])

(let [[before after] *command-line-args*
      roots #(str/split (str/trim (slurp %)) #":")
      prior (roots before) current (roots after)
      old "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/60e1f69bbb06766c36ca9ef0c34af51a97e69c34/"
      new "https___github.com_chucklehead-dev_jolt-otel-clickhouse.git/6a98b94213648fcf52036d72019281fc37d0165c/"
      expected (map #(str/replace % old new) prior)
      exact? #(= (frequencies expected) (frequencies %))]
  (assert (= 1 (count (filter #(str/includes? % old) prior))))
  (assert (= 1 (count (filter #(str/includes? % new) current))))
  (assert (exact? current))
  ;; Actual immutable receipt data is untouched; reject a changed provider in
  ;; memory to prove that the oracle is not merely comparing component counts.
  (assert (not (exact? (map #(str/replace % "649c993780a0f269938c9c6afa3bae7e9709bb48"
                                           "unapproved-provider") current))))
  (assert (not-any? #(str/includes? % "/incubator/codec/") current))
  (prn {:scope :saved-effective-classpath-receipts
        :only-exporter-source-root-changed true
        :other-source-root-frequencies-unchanged true
        :changed-provider-negative-control :rejected
        :experimental-codec-not-resolved true}))
