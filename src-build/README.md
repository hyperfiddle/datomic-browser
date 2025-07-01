# How to build for prod – WIP

docker build --build-arg VERSION=$(git rev-parse HEAD) -t hyperfiddle-starter-app:latest .
docker run --rm -it -p 8080:8080 hyperfiddle-starter-app:latest
