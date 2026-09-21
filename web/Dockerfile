FROM node:24-alpine AS builder
WORKDIR /app
ENV ASTRO_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG NAKVALI_CONTACT_EMAIL=whekins@gmail.com
# Public contact, not a secret. Fail closed rather than publish legal placeholders.
RUN test -n "$NAKVALI_CONTACT_EMAIL" && NAKVALI_CONTACT_EMAIL="$NAKVALI_CONTACT_EMAIL" npm run check

FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
