FROM solr:8.8

USER root

RUN install -o $SOLR_USER -g $SOLR_GROUP\
    -d /opt/solr/server/solr/configsets/zdl/lib/

USER $SOLR_USER

ADD --chown=8983:8983\
    https://github.com/textexploration/mtas/releases/download/v8.8.2.0/mtas-8.8.2.0.jar\
    /opt/solr/server/solr/configsets/zdl/lib/

ADD --chown=8983:8983\
    https://repo1.maven.org/maven2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar\
    /opt/solr/server/solr/configsets/zdl/lib/

ADD --chown=8983:8983\
    conf\
    /opt/solr/server/solr/configsets/zdl/conf

ADD --chown=8983:8983\
    lang\
    /opt/solr/server/solr/configsets/zdl/lang

CMD ["solr-precreate", "zdl", "/opt/solr/server/solr/configsets/zdl"]
