FROM clojure:temurin-17-tools-deps-1.12.0.1501 AS datomic-fixtures
WORKDIR /app
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends unzip curl wget
COPY datomic_fixtures_mbrainz_small.sh datomic_fixtures_mbrainz_small.sh
COPY datomic_fixtures_mbrainz_full.sh datomic_fixtures_mbrainz_full.sh
RUN ./datomic_fixtures_mbrainz_small.sh
# RUN ./datomic_fixtures_mbrainz_full.sh
# Shaves 3Gb+ of docker image
RUN rm state/*.tar
RUN rm state/*.zip

FROM clojure:temurin-17-tools-deps-1.12.0.1501 AS build
WORKDIR /app
COPY deps.edn deps.edn
RUN clojure -M -e ::ok              # preload deps
RUN clojure -A:build -M -e ::ok     # preload build deps
COPY src src
COPY src-prod src-prod
COPY src-build src-build
COPY resources resources
RUN clojure -X:build uberjar :build/jar-name '"app.jar"'

FROM amazoncorretto:17 AS app
# FROM clojure:temurin-17-tools-deps-1.12.0.1501 AS app
WORKDIR /app
COPY run_datomic.sh run_datomic.sh
COPY --from=datomic-fixtures /app/state state
COPY --from=build /app/target/app.jar app.jar

EXPOSE 8080
CMD ./run_datomic.sh && java -cp app.jar clojure.main -m datomic-browser.main datomic-uri datomic:dev://localhost:4334/mbrainz-1968-1973
# CMD ./run_datomic.sh && java -cp app.jar clojure.main -m datomic-browser.main datomic-uri datomic:dev://localhost:4334/mbrainz-full
