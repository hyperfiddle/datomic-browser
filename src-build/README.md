# How to build

## Uberjar

```shell
clj -X:build uberjar :build/jar-name '"app.jar"'
java -cp target/app.jar clojure.main -m datomic-browser.main datomic-uri 'datomic:dev://localhost:4334/*'
```

## Docker

```shell
docker build -t datomic-browser:latest .
docker run --rm -it -p 8080:8080 datomic-browser:latest
```

## Fly

```shell
fly deploy
```

## Running on a local DynamoDB

```shell
./datomic_fixtures_mbrainz_small_ddb.sh
./run_datomic_ddb.sh
AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy clj -A:dev -M -m datomic-browser.main datomic-uri 'datomic:ddb-local://localhost:8000/datomic/*'
```
