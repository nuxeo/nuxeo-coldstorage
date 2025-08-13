ARG NUXEO_VERSION=2023

FROM docker-private.packages.nuxeo.com/nuxeo/nuxeo:${NUXEO_VERSION}

ARG BUILD_TAG=unknown
ARG SCM_REF=unknown
ARG VERSION=unknown

LABEL org.nuxeo.coldstorage.build-tag=$BUILD_TAG
LABEL org.nuxeo.coldstorage.scm-ref=$SCM_REF
LABEL org.nuxeo.coldstorage.version=$VERSION

USER root
# install RPM Fusion free repository
RUN --mount=type=secret,id=nuxeo-private,target=/etc/yum.repos.d/nuxeo-private.repo \
    dnf -y install ffmpeg-nuxeo \
    && dnf clean all
USER 900

RUN --mount=type=secret,id=CLID,env=CLID \
    --mount=type=secret,id=CONNECT_URL,env=CONNECT_URL \
    /install-packages.sh --clid ${CLID} --connect-url ${CONNECT_URL} \
      amazon-s3-online-storage \
      nuxeo-web-ui

RUN --mount=type=bind,source=./target,target=/tmp/local-packages  \
    /install-packages.sh --offline /tmp/local-packages/*.zip
