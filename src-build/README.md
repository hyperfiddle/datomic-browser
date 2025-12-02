# How to build for prod

## Uberjar

clojure -X:prod:build uberjar :build/jar-name "datomic-browser.jar"
java -cp target/datomic-browser.jar clojure.main -m prod datomic-uri 'datomic:dev://localhost:4334/*'

## Uberjar with jetty9

You must have edited `src-prod/prod.cljc` to use jetty9. See comments in code.

clojure -X:jetty9:prod:build uberjar :aliases '[:jetty9 :prod]' :build/jar-name "datomic-browser.jar"
java -cp target/datomic-browser.jar clojure.main -m prod datomic-uri 'datomic:dev://localhost:4334/*'


## Docker

docker build --build-arg VERSION=$(git rev-parse HEAD) -t hyperfiddle-starter-app:latest .
docker run --rm -it -p 8080:8080 hyperfiddle-starter-app:latest

## Classpath integration

1. Add `com.hyperfiddle/hyperfiddle` to your deps.
2. Reproduce the [electric3-starter-app](https://gitlab.com/hyperfiddle/electric3-starter-app) integration into your project.
   Follow instructions there. You should end up with:
   - electric server and client running in your application
   - hot code reload with shadow-cljs working.
3. Copy `src/dustingetz/datomic_browser2.cljc` and `src/dustingetz/hyperfiddle_datomic_browser_demo` into your source path.
4. Adapt the client and server entrypoints you've got from the starter app so they match `src-dev/dev.cljc` from this repo. They only differs slightly.
