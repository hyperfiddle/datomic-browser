(ns dev-jetty9 ; require :jetty9 deps alias
  (:require
   [dustingetz.hyperfiddle-datomic-browser-demo :refer [hyperfiddle-demo-boot]]
   #?(:clj [dustingetz.datomic-contrib2 :refer [datomic-uri-db-name]])

   #?(:clj [shadow.cljs.devtools.api :as shadow-cljs-compiler])
   #?(:clj [shadow.cljs.devtools.server :as shadow-cljs-compiler-server])
   #?(:clj [clojure.tools.logging :as log])

   #?(:clj [ring.adapter.jetty :as ring])
   #?(:clj [ring.util.response :as ring-response])
   #?(:clj [ring.middleware.resource :refer [wrap-resource]])
   #?(:clj [ring.middleware.content-type :refer [wrap-content-type]])
   #?(:clj [hyperfiddle.electric-jetty9-ring-adapter3 :refer [electric-jetty9-ws-install]]) ; jetty 9
   ))

(comment (-main)) ; repl entrypoint

#?(:clj (defn next-available-port-from [start] (first (filter #(try (doto (java.net.ServerSocket. %) .close) % (catch Exception _ (println (format "Port %s already taken" %)) nil)) (iterate inc start)))))

#?(:clj ; server entrypoint
   (defn -main [& args]
     (let [{:keys [datomic-uri http-port]} (first args)
           http-port (or http-port (next-available-port-from 8080))
           datomic-uri (or datomic-uri "datomic:dev://localhost:4334/*")] ; dev only default
       (assert (some? datomic-uri) "Missing `:datomic-uri`. See README.md")
       (assert (string? datomic-uri) "Invalid `:datomic-uri`. See README.md")
       (assert (= "*" (datomic-uri-db-name datomic-uri)) "`:datomic-uri`. Must be a transactor URI (must ends with \"/*\")")

       (shadow-cljs-compiler-server/start!)
       (shadow-cljs-compiler/watch :dev)

       (def server (ring/run-jetty
                     (-> ; ring middlewares – applied bottom up:
                       (fn [ring-request] ; 3. index page fallback
                         (-> (ring-response/resource-response "index.dev.html" {:root "public/hyperfiddle-starter-app"})
                           (ring-response/content-type "text/html")))
                       (wrap-resource "public") ; 2. serve assets from disk.
                       (wrap-content-type)) ; 1. boilerplate – to server assets with correct mime/type.
                     {:host "0.0.0.0", :port http-port, :join? false
                      :configurator (fn [server] ; tune jetty server – larger websocket messages, longer timeout – this is a temporary tweak
                                      (electric-jetty9-ws-install server "/" (fn [ring-request] (hyperfiddle-demo-boot ring-request datomic-uri))) ; jetty 9
                                      )}))
       (log/info (format "👉 http://0.0.0.0:%s" http-port)))))

(declare browser-process)
#?(:cljs ; client entrypoint
   (defn ^:dev/after-load ^:export -main []
     (set! browser-process
       ((hyperfiddle-demo-boot nil nil) ; boot client-side Electric process
        #(js/console.log "Reactor success:" %)
        #(js/console.error "Reactor failure:" %)))))

#?(:cljs
   (defn ^:dev/before-load stop! [] ; for hot code reload at dev time
     (when browser-process (browser-process)) ; tear down electric browser process
     (set! browser-process nil)))

(comment
  (shadow-cljs-compiler-server/stop!)
  (.stop server) ; stop jetty server
  )
