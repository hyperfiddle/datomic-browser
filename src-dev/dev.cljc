(ns dev
  (:require
   [dustingetz.hyperfiddle-datomic-browser-demo :refer [hyperfiddle-demo-boot]]

   #?(:clj [shadow.cljs.devtools.api :as shadow-cljs-compiler])
   #?(:clj [shadow.cljs.devtools.server :as shadow-cljs-compiler-server])
   #?(:clj [clojure.tools.logging :as log])

   #?(:clj [ring.adapter.jetty :as ring])
   #?(:clj [ring.util.response :as ring-response])
   #?(:clj [ring.middleware.params :refer [wrap-params]])
   #?(:clj [ring.middleware.resource :refer [wrap-resource]])
   #?(:clj [ring.middleware.content-type :refer [wrap-content-type]])
   #?(:clj [hyperfiddle.electric-ring-adapter3 :refer [wrap-electric-websocket]]) ; jetty 10+
   ;; #?(:clj [hyperfiddle.electric-jetty9-ring-adapter3 :refer [electric-jetty9-ws-install]]) ; jetty9
   ))

(comment (-main {:datomic-uri "datomic:dev://localhost:4334/mbrainz-1968-1973"})) ; repl entrypoint

#?(:clj (defn next-available-port-from [start] (first (filter #(try (doto (java.net.ServerSocket. %) .close) % (catch Exception _ (println (format "Port %s already taken" %)) nil)) (iterate inc start)))))

#?(:clj ; server entrypoint
   (defn -main [& args]
     (let [{:keys [datomic-uri http-port]} (first args)
           http-port (or http-port (next-available-port-from 8080))]
       (assert (some? datomic-uri) "Missing `:datomic-uri`. See README.md")
       (assert (string? datomic-uri) "Invalid `:datomic-uri`. See README.md")

       (shadow-cljs-compiler-server/start!)
       (shadow-cljs-compiler/watch :dev)

       (def server (ring/run-jetty
                     (-> ; ring middlewares – applied bottom up:
                       (fn [ring-request] ; 5. index page fallback
                         (-> (ring-response/resource-response "index.dev.html" {:root "public/hyperfiddle-starter-app"})
                           (ring-response/content-type "text/html")))
                       (wrap-resource "public") ; 4. serve assets from disk.
                       (wrap-content-type) ; 3. boilerplate – to server assets with correct mime/type.
                       (wrap-electric-websocket ; 2. install Electric server.
                         (fn [ring-request] (hyperfiddle-demo-boot ring-request datomic-uri))) ; boot server-side Electric process
                       (wrap-params)) ; 1. boilerplate – parse request URL parameters.
                     {:host "0.0.0.0", :port http-port, :join? false
                      :configurator (fn [server] ; tune jetty server – larger websocket messages, longer timeout – this is a temporary tweak
                                      #_(electric-jetty9-ws-install server "/" (fn [ring-request] (hyperfiddle-demo-boot ring-request datomic-uri)))
                                      (org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer/configure
                                        (.getHandler server)
                                        (reify org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer$Configurator
                                          (accept [_this _servletContext wsContainer]
                                            (.setIdleTimeout wsContainer (java.time.Duration/ofSeconds 60)) ; default is 30
                                            (.setMaxBinaryMessageSize wsContainer (* 100 1024 1024)) ; typical compressed message size is of a few KBs. Set to 100M for demo.
                                            (.setMaxTextMessageSize wsContainer (* 100 1024 1024))))))}))  ; 100M - for demo.
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
