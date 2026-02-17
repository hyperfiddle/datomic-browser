(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.tools.logging :as log]))

(def class-dir "target/classes")

(defn uberjar
  "Build uberjar for deployment. No client build needed — compiled JS
   comes from the hyperfiddle-agent JAR on the classpath.
   Usage: clj -X:build uberjar
          clj -X:build uberjar :build/jar-name '\"app.jar\"'"
  [{:keys [::jar-name] :as args}]
  (log/info 'uberjar (pr-str args))
  (b/delete {:path "target"})
  (b/copy-dir {:target-dir class-dir :src-dirs ["src" "resources"]})
  (let [jar-name (or (some-> jar-name str) "datomic-browser.jar")]
    (b/uber {:class-dir class-dir
             :uber-file (str "target/" jar-name)
             :basis     (b/create-basis {:project "deps.edn"})})
    (log/info jar-name)))
