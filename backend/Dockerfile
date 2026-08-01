# Build stage
FROM golang:1.26 AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /bin/api ./cmd/api \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /bin/healthcheck ./cmd/healthcheck

# The migration image provides the pinned CLI and a minimal shell for the
# startup script. Migrations complete before the API opens its listener.
FROM migrate/migrate:v4.18.3

COPY --from=builder /bin/api /api
COPY --from=builder /bin/healthcheck /healthcheck
COPY migrations /migrations
COPY docker-entrypoint.sh /docker-entrypoint.sh

EXPOSE 8080
USER 65532:65532

ENTRYPOINT ["/bin/sh", "/docker-entrypoint.sh"]
