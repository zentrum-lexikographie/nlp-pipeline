FROM solr:9

USER root

RUN install -o $SOLR_USER -g $SOLR_GROUP\
    -d /opt/solr/server/solr/configsets/zdl/lib/

USER $SOLR_USER

ADD --chown=8983:8983\
    mtas/target/mtas-9.10.0.0-SNAPSHOT.jar\
    /opt/solr/server/solr/configsets/zdl/lib/

ADD --chown=8983:8983\
    conf\
    /opt/solr/server/solr/configsets/zdl/conf

ADD --chown=8983:8983\
    lang\
    /opt/solr/server/solr/configsets/zdl/lang

CMD ["solr-precreate", "zdl", "/opt/solr/server/solr/configsets/zdl"]
