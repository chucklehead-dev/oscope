(ns oscope.typed.controls.wrong-command-variant
  "Mutant: an export variant cannot satisfy the query-command contract."
  (:require [typed.clojure :as t]
            [oscope.typed.contracts]))

(t/ann command oscope.typed.contracts/QueryCommand)
(def command
  {:oscope.command/version 1
   :command/type :export
   :request-id 1
   :selection {:signal :spans :field :service-name :window :15m :limit 12}})
