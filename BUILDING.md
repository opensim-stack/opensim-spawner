# Building

Build and publish multiarch image.

## Local Build

```bash
docker build -t opensim-spawner:local .
```

### Run Local

```bash
docker run --rm \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e OPENSIM_SPAWNER_HTTP_HOST=0.0.0.0 \
  -e OPENSIM_SPAWNER_HTTP_PORT=8993 \
  -e OPENSIM_SPAWNER_FIRST_PORT=9000 \
  -e OPENSIM_SPAWNER_LAST_PORT=9015 \
  -e OPENSIM_CONSOLE_USER=ConsoleUser \
  -e OPENSIM_CONSOLE_PASS=ConsolePass \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v $(pwd)/.local/config:/config \
  -v $(pwd)/.local/data:/data \
  -v $(pwd)/.local/workspace:/workspace \
  -p 8993:8993 \
  opensim-spawner:local
```

Note: if your host uses rootless Docker, mount your user socket instead (for example
`-v /run/user/1000/docker.sock:/var/run/docker.sock`) and keep `DOCKER_HOST` set to
`unix:///var/run/docker.sock`.

## Publish

### Setup

Create/use a buildx builder once:

```bash
docker buildx create --name multiarch --use
docker buildx inspect --bootstrap
```

### Build

Build and push Linux AMD64 + ARM64:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t bithatch/opensim-spawner \
  -t bithatch/opensim-spawner:$(date +%Y%m%d) \
  --push \
  .
```

## Automated Publish (GitHub Actions)

This repository includes `.github/workflows/docker-publish.yml` to automatically build and push a multiarch image to Docker Hub.

### Triggers

- Pushes to `master` or `main` when `Dockerfile`, `docker/**`, `src/**`, `pom.xml`, or the workflow itself changes
- Git tags matching `v*`
- Manual `workflow_dispatch`

### Required Repository Secrets

- `DOCKERHUB_USERNAME`: Docker Hub username or org robot account name
- `DOCKERHUB_TOKEN`: Docker Hub access token (recommended) or password

### Published Platforms and Tags

- Platforms: `linux/amd64`, `linux/arm64`
- Tags (default branch): `latest`, `YYYYMMDD`, and `sha-<commit>`
- Tags (tag builds): `<git-tag>` and `sha-<commit>`
