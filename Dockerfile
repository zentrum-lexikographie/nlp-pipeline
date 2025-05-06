FROM clojure:temurin-21-jammy

WORKDIR /service

COPY deps.edn .

RUN clojure -A:build -P

COPY ./ .

RUN clojure -T:build compile-java

ENTRYPOINT ["clojure", "-X", "zdl.nlp.lab/start!"]
