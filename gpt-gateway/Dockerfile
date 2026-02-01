FROM clojure:temurin-24-tools-deps

RUN mkdir -p /service
WORKDIR /service

COPY deps.edn /service/

RUN clojure -P

COPY ./ /service

ENTRYPOINT ["clojure", "-J-Dclojure.main.report=stderr", "-X", "zdl.gpt-gateway/serve!"]
