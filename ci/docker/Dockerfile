ARG BASE_IMAGE=docker-private.packages.nuxeo.com/nuxeo/nuxeo:11.4.34

FROM ${BASE_IMAGE}

ARG BUILD_TAG
ARG SCM_REF
ARG VERSION

LABEL org.nuxeo.build-tag=$BUILD_TAG
LABEL org.nuxeo.scm-ref=$SCM_REF
LABEL org.nuxeo.version=$VERSION

COPY --chown=900:0 nuxeo-coldstorage-package-*.zip /packages/
COPY --chown=900:0 templates/s3-coldstorage /opt/nuxeo/server/templates/s3-coldstorage

USER root
RUN /install-packages.sh --offline /packages/*.zip
RUN chown -R 900:0 $NUXEO_HOME \
  && chmod -R g+rwX $NUXEO_HOME
USER 900
