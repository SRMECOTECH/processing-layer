# Builds the PulseStream dashboard into a small image, in two stages:
#   1. "build"   : Node installs the packages and runs next build (standalone output)
#   2. "runtime" : only Node and the standalone server - no source code, no dev packages
#
# Build it:  docker build -t gps-dashboard-ui ./gps-dashboard-ui
# (docker compose builds it for you - see docker-compose.yml)
# The GPS service address is read at start-up from GPS_API_URL (default http://localhost:8080).
# Sign-in: DASHBOARD_USERNAME / DASHBOARD_PASSWORD (default abc / abc) and AUTH_SECRET, also read at start-up.

# ---------- stage 1: build ----------
FROM node:22-alpine AS build
WORKDIR /workspace

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY next.config.ts tsconfig.json ./
COPY src src
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# ---------- stage 2: runtime ----------
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# Do not run as root inside the container.
RUN adduser -S -u 1001 dashboard
USER dashboard

COPY --from=build --chown=dashboard /workspace/.next/standalone ./
COPY --from=build --chown=dashboard /workspace/.next/static ./.next/static

EXPOSE 3000
CMD ["node", "server.js"]
