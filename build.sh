#!/bin/bash
set -e
set -x
test -d target && rm -rf target

echo "Building frontend code"
time clojure -M:shadow-cli release :main

echo "Packing backend code"
time clojure -A:pack # --no-libs | --no-project
# Infrequently changed external dependencies:
mkdir -p target/docker/deps-ext
cp target/lib/*.jar target/docker/deps-ext/
# The app
cp target/app.jar target/docker/

## The following has been moved into buildspec.yml and is thus executed by AWS CodeBuild
#echo "Building docker"
#SHA=$(git rev-parse HEAD | cut -c 1-8)
#docker build --build-arg "arg_git_sha=$SHA" -f Dockerfile -t billing-app target/docker
