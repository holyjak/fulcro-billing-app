FROM azul/zulu-openjdk-alpine:11-jre

WORKDIR /app
# Copy from the build dir, i.e. ./target/docker/:
COPY /deps-ext/* /app/
COPY /app.jar /app/

CMD ["java", "-Dfile.encoding=UTF-8", "-cp", "*", "clojure.main", "-m", "billing-app.main"]

# Pass the value to docker build via --build-arg arg_git_sha=...:
ARG arg_git_sha
ENV GIT_SHA=$arg_git_sha
