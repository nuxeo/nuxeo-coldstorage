ARG BASE_IMAGE=docker-private.packages.nuxeo.com/nuxeo/nuxeo:11.4.34

FROM ${BASE_IMAGE}

ARG BUILD_TAG
ARG SCM_REF
ARG VERSION

LABEL org.nuxeo.build-tag=$BUILD_TAG
LABEL org.nuxeo.scm-ref=$SCM_REF
LABEL org.nuxeo.version=$VERSION

USER root
# install RPM Fusion free repository
RUN yum -y localinstall --nogpgcheck https://mirrors.rpmfusion.org/free/el/rpmfusion-free-release-7.noarch.rpm
# install ffmpeg and ImageMagick packages
RUN yum -y install ffmpeg ImageMagick
USER 900

COPY --chown=900:0 nuxeo-coldstorage-package-*.zip /packages/
