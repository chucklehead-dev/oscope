# Static historical server, without a registry parent or entrypoint wrapper.
FROM scratch
COPY minio /minio
COPY --chmod=1777 tmp/ /tmp/
ENV MINIO_BROWSER=off MINIO_UPDATE=off
# /data is supplied by the existing fixture. Scratch has no writable HOME;
# the non-root woven fixture stores disposable config alongside its own data.
ENTRYPOINT ["/minio", "--config-dir", "/data/.minio-config"]
