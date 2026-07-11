# Build stage
FROM golang:1.26 AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /bin/api ./cmd/api

# Final stage: distroless static image with CA certificates included
FROM gcr.io/distroless/static:nonroot

COPY --from=builder /bin/api /api

EXPOSE 8080
USER nonroot:nonroot

ENTRYPOINT ["/api"]
