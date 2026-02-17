(ns dev
  "Dev entrypoint. Starts the Datomic Browser with default settings."
  (:require [datomic-browser.main :as main]))

(comment (-main))

(defn -main [& _]
  (main/-main "datomic-uri" "datomic:dev://localhost:4334/*"))
