# Build the static dashboard, then serve it from nginx together with an /api
# reverse proxy so the whole app is one origin (no CORS, no mixed content).
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Empty base URL: the app fetches /api/... on the same origin as the page.
# nginx serves that origin and proxies /api to ingest-service.
ENV VITE_API_BASE_URL=""
RUN npm run build

FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
