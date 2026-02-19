(ns datomic-browser.main
  "Datomic Browser — self-contained single process.

   Usage:
     clj -M -m datomic-browser.main                                              ;; defaults
     clj -M -m datomic-browser.main datomic-uri datomic:dev://localhost:4334/*   ;; explicit URI
     clj -M -m datomic-browser.main http-port 9090                               ;; custom port"
  (:require [hyperfiddle.navigator-agent :as agent]
            [dustingetz.nav-datomic :as nav]))

(defn -main [& {:strs [datomic-uri http-port]}]
  (let [port (or (some-> http-port parse-long) 8080)
        datomic-uri (or datomic-uri "datomic:dev://localhost:4334/*")]
    (agent/serve! nav/rich-sitemap (nav/make-setup-fn datomic-uri) :port port)))
