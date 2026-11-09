# CA roots for outgoing HTTPS (Google OAuth + FCM); the server itself is a static binary.
FROM alpine:3.20 AS ca
RUN apk add --no-cache ca-certificates

FROM scratch
COPY --from=ca /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY call-server /call-server
ENV DB=/data/db.json
USER 65534
EXPOSE 8080
ENTRYPOINT ["/call-server"]
