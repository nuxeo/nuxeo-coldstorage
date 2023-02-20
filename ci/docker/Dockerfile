ARG NUXEO_VERSION=2021

FROM docker-private.packages.nuxeo.com/nuxeo/nuxeo:${NUXEO_VERSION}

ARG BUILD_TAG=unknown
ARG SCM_REF=unknown
ARG VERSION=unknown

LABEL org.nuxeo.coldstorage.build-tag=$BUILD_TAG
LABEL org.nuxeo.coldstorage.scm-ref=$SCM_REF
LABEL org.nuxeo.coldstorage.version=$VERSION

USER root
# install RPM Fusion free repository
RUN yum -y localinstall --nogpgcheck https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-7.noarch.rpm \
    # install ffmpeg package
    && yum -y install ffmpeg \
    && yum clean all
USER 900

COPY --chown=900:0 target/nuxeo-coldstorage-package-$VERSION.zip /packages/
