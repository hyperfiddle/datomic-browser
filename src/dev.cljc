;; run with `clj -X:demo dev/-main`
;; supply your datomic connection string as `clj -X:demo dev/-main :datomic-uri '"my-datomic-uri-here"'`

(ns dev
  (:require
   [dustingetz.hyperfiddle-datomic-browser-demo :refer [hyperfiddle-demo-boot]]

   #?(:clj [hyperfiddle.electric-ring-adapter3 :refer [wrap-electric-websocket]]) ; jetty 10+
   ;; #?(:clj [hyperfiddle.electric-jetty9-ring-adapter3 :refer [electric-jetty9-ws-install]]) ; jetty9
   #?(:clj [ring.middleware.resource :refer [wrap-resource]])
   #?(:clj [ring.middleware.content-type :refer [wrap-content-type]])
   #?(:clj [ring.util.response :as ring-response])
   #?(:clj [ring.adapter.jetty :as ring])

   #?(:clj [shadow.cljs.devtools.api :as shadow-cljs-compiler])
   #?(:clj [shadow.cljs.devtools.server :as shadow-cljs-compiler-server])
   ))

#?(:clj
   (defn -main [& args]
     (let [{:keys [datomic-uri]} (first args)]
       (shadow-cljs-compiler-server/start!)
       (shadow-cljs-compiler/watch :demo)
       (ring/run-jetty
         (-> (fn [ring-request] (-> (ring-response/resource-response "index.html" {:root "public/hyperfiddle-demo"})
                                  (ring-response/header "Content-Type" "text/html")))
           (wrap-resource "public")     ; serves index.html, css, js, etc.
           (wrap-content-type)
           (wrap-electric-websocket (fn [ring-request] (hyperfiddle-demo-boot ring-request datomic-uri))) ; jetty 10+
           )
         {:host "0.0.0.0", :port 8080 :join? false
          :configurator (fn [server] ; tune jetty server – larger websocket messages, longer timeout
                          #_(electric-jetty9-ws-install server "/" (fn [ring-request] (hyperfiddle-demo-boot ring-request)))
                          (org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer/configure (.getHandler server)
                            (reify org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer$Configurator
                              (accept [_this _servletContext wsContainer]
                                (.setIdleTimeout wsContainer (java.time.Duration/ofSeconds 60)) ; default is 30s
                                (.setMaxBinaryMessageSize wsContainer (* 100 1024 1024))
                                (.setMaxTextMessageSize wsContainer (* 100 1024 1024))))))})
       (println "Demo running at http://0.0.0.0:8080/"))))


(declare browser-process)
#?(:cljs
   (defn ^:dev/after-load -main []
     (set! browser-process
       ((hyperfiddle-demo-boot nil nil)
        #(js/console.debug "Electric client process terminated successfully" %)
        #(js/console.debug "Electric client process terminated" %)))))

#?(:cljs (defn ^:dev/before-load stop! []
           (when browser-process (browser-process))
           (set! browser-process nil)))
