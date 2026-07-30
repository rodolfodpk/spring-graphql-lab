FROM busybox:1.37.0-musl AS health-tools
FROM ghcr.io/apollographql/router:v2.15.0
COPY --from=health-tools /bin/busybox /bin/busybox
