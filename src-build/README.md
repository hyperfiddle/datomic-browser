# How to build for prod

## Uberjar (Jetty 10+)

```shell
clojure -X:prod:build uberjar :version '"'$(git rev-parse HEAD)'"' :build/jar-name '"datomic-browser.jar"'
java -cp target/datomic-browser.jar clojure.main -m prod datomic-uri 'datomic:dev://localhost:4334/*'
```

## Uberjar (Jetty 9)

```shell
clojure -X:jetty9:prod:build uberjar :version '"'$(git rev-parse HEAD)'"' :shadow-build :prod-jetty9 :aliases '[:jetty9 :prod]' :build/jar-name '"datomic-browser.jar"'
java -cp target/datomic-browser.jar clojure.main -m prod-jetty9 datomic-uri 'datomic:dev://localhost:4334/*'
```

## Docker

```shell
docker build --build-arg VERSION=$(git rev-parse HEAD) -t datomic-browser:latest .
docker run --rm -it -p 8080:8080 datomic-browser:latest
```

## Fly

```shell
fly deploy --remote-only --build-arg VERSION=$(git rev-parse HEAD)
```

## Classpath integration

1. Add `com.hyperfiddle/hyperfiddle` to your deps.
2. Reproduce the [electric3-starter-app](https://gitlab.com/hyperfiddle/electric3-starter-app) integration into your project.
   Follow instructions there. You should end up with:
   - electric server and client running in your application
   - hot code reload with shadow-cljs working.
3. Copy `src/dustingetz/datomic_browser2.cljc` and `src/dustingetz/hyperfiddle_datomic_browser_demo` into your source path.
4. Adapt the client and server entrypoints you've got from the starter app so they match `src-dev/dev.cljc` from this repo. They only differ slightly.

## Running on a local Dynamo DB

```shell
./datomic_fixtures_mbrainz_small_ddb.sh
./run_datomic_ddb.sh
AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy clj -X:dev dev/-main :datomic-uri '"datomic:ddb-local://localhost:8000/datomic/*"'
```