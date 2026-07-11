FROM rust:1.94-slim AS builder
WORKDIR /src
COPY . .
RUN cargo build --release -p fusion-worker

FROM gcr.io/distroless/cc-debian12
COPY --from=builder /src/target/release/fusion-worker /usr/local/bin/fusion-worker
ENTRYPOINT ["/usr/local/bin/fusion-worker"]
