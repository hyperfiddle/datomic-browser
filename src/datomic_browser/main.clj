(ns datomic-browser.main
  "Datomic Browser — self-contained single process.
   Starts a cloud proxy on the given port and auto-connects a Datomic agent
   that serves the Datomic browser UI (from the hyperfiddle-agent JAR).

   Usage:
     clj -M -m datomic-browser.main                                              ;; defaults
     clj -M -m datomic-browser.main datomic-uri datomic:dev://localhost:4334/*    ;; explicit URI
     clj -M -m datomic-browser.main http-port 9090                               ;; custom port"
  (:require [clojure.tools.logging :as log]
            [hyperfiddle.cloud-proxy :as proxy]
            [hyperfiddle.navigator-agent :as agent]
            [hyperfiddle.nav-cloud-agents :as nav-cloud-agents]
            [dustingetz.nav-datomic :as nav]))

(defn -main
  "Entrypoint. Starts cloud proxy + auto-connects datomic agent.
   Args are clojure.main style key-value strings."
  [& {:strs [datomic-uri http-port]}]
  (let [port (or (some-> http-port parse-long) 8080)
        datomic-uri (or datomic-uri "datomic:dev://localhost:4334/*")]
    (log/info "Starting Datomic Browser" (pr-str {:datomic-uri datomic-uri :port port}))

    ;; 1. Start cloud proxy (serves the agent web UI from hyperfiddle-agent JAR)
    (def server (proxy/start-proxy! :port port))

    ;; 2. Auto-connect admin agent (shows connected agents dashboard)
    (def admin-agent
      (agent/connect! (str "ws://localhost:" port "/agent?id=admin")
        nav-cloud-agents/sitemap
        (fn [] {#'nav-cloud-agents/*agents* proxy/agents})))

    ;; 3. Auto-connect datomic agent
    (def datomic-agent
      (agent/connect! (str "ws://localhost:" port "/agent?id=datomic")
        nav/rich-sitemap
        (nav/make-setup-fn datomic-uri)))

    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (agent/disconnect! datomic-agent)
                 (agent/disconnect! admin-agent)
                 (proxy/stop-proxy! server))))

    (log/info (format "Browse databases: http://datomic.localhost:%d" port))
    (log/info (format "Admin dashboard:  http://admin.localhost:%d" port))))
