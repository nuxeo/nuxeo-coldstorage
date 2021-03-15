ARG BASE_IMAGE=nuxeo/nuxeo:10.10

FROM ${BASE_IMAGE}

ARG BUILD_TAG
ARG SCM_REF
ARG VERSION

LABEL org.nuxeo.build-tag=$BUILD_TAG
LABEL org.nuxeo.scm-ref=$SCM_REF
LABEL org.nuxeo.version=$VERSION

COPY --chown=1000:0 nuxeo-coldstorage-package-*.zip /packages/nuxeo-coldstorage-package.zip
COPY --chown=1000:0 templates/s3-coldstorage /opt/nuxeo/server/templates/s3-coldstorage

USER root

# install RPM Fusion free repository
RUN yum -y localinstall --nogpgcheck https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-7.noarch.rpm
# install ffmpeg package
RUN yum -y install ffmpeg

USER 1000
