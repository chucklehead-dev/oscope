(require '[clojure.edn :as edn])

(let [root (first *command-line-args*)
      arms ["1-baseline" "2-candidate" "3-candidate" "4-baseline"]
      reports (mapv #(edn/read-string (slurp (str root "/" % ".writer.edn"))) arms)]
  (doseq [[arm report] (map vector arms reports)]
    (let [reader (edn/read-string (slurp (str root "/" arm ".reader.edn")))]
      (assert (= {:rows 4608 :core-columns 22 :independent-reader true}
                 (dissoc reader :nanos)))
      (prn arm (select-keys report [:window-rows-per-second :latency
                                   :estimated-allocated-bytes-per-row]))))
  (doseq [[label indices] [[:baseline [0 3]] [:candidate [1 2]]]]
    (let [selected (mapv reports indices)
          ordered (vec (sort (mapcat :samples-nanos selected)))
          q (fn [p] (/ (nth ordered (dec (int (Math/ceil (* p (count ordered)))))) 1e6))]
      (prn label {:pooled-rows-per-second
                  (/ (* 8192 1e9) (reduce + (map :window-nanos selected)))
                  :estimated-mean-bytes-per-row
                  (/ (reduce + (map :estimated-allocated-bytes-per-row selected)) 2)
                  :p50-ms (q 0.5) :p90-ms (q 0.9) :p95-ms (q 0.95) :p99-ms (q 0.99)}))))
