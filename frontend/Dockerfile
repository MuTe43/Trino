# Node 22, matching the pinned stack.

FROM node:22-alpine AS deps
WORKDIR /source
COPY package.json package-lock.json ./
# npm ci, not npm install: the lockfile is the dependency list, and an image
# that resolved its own versions would not be the thing that was tested.
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /source
COPY --from=deps /source/node_modules ./node_modules
COPY . .

# Baked in at build time, not read at runtime. NEXT_PUBLIC_* values are
# substituted into the client bundle during `next build`, so this cannot be a
# compose `environment:` entry -- it has to be a build arg.
#
# It is the address the BROWSER uses, which is why it is a published host port
# and not the container name: the map and the SSE stream run in the visitor's
# browser, which has no route to `api` on the compose network. Getting this
# wrong produces a page that renders and then fails every request, with the
# container logs completely silent.
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8081
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}

RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

# node:22-alpine already ships an unprivileged `node` user.
USER node

# The three pieces `output: "standalone"` splits the build into. public/ and
# .next/static/ are not copied into standalone by Next itself -- it prints a
# note saying so -- and leaving them out yields a site with no CSS and no
# favicon that otherwise works, which is a confusing way to fail.
COPY --from=build --chown=node:node /source/.next/standalone ./
COPY --from=build --chown=node:node /source/.next/static ./.next/static
COPY --from=build --chown=node:node /source/public ./public

EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0
CMD ["node", "server.js"]
