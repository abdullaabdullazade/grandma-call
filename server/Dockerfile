# Builds the server from source: no Go needed on the host.
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /call-server

FROM alpine:3.20 AS ca
RUN apk add --no-cache ca-certificates

FROM scratch
COPY --from=ca /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /call-server /call-server
ENV DB=/data/db.json
USER 65534
EXPOSE 8080
ENTRYPOINT ["/call-server"]
