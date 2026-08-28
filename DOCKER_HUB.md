# opensim-spawner

`opensim-spawner` is an HTTP coordinator service that creates and manages OpenSim AI bot container sets from a strict level-based profile.

It is intended to be used as part of the **OpenSim Stack** project:
**"A docker stack to get an AI integrated virtual world up and running in minutes."**

## What This Image Does

- Exposes a simple HTTP API to list, check, and create bot instances
- Creates OpenSim bot users through the OpenSimulator REST console bridge
- Allocates dynamic per-bot ports and persists instance state under `/data`
- Creates and starts related containers (such as `opensim-opencode` and `opensim-metaverse2mcp`) using Docker SDK

## Quick Start

Run the container with mounted stack directories and OpenSim console credentials:

```bash
docker run --rm \
  -e OPENSIM_SPAWNER_HTTP_HOST=0.0.0.0 \
  -e OPENSIM_SPAWNER_HTTP_PORT=8993 \
  -e OPENSIM_SPAWNER_TOKEN= \
  -e OPENSIM_SPAWNER_FIRST_PORT=12345 \
  -e OPENSIM_CONSOLE_USER=ConsoleUser \
  -e OPENSIM_CONSOLE_PASS=ConsolePass \
  -v $(pwd)/config:/config \
  -v $(pwd)/data:/data \
  -v $(pwd)/workspace:/workspace \
  -p 8993:8993 \
  bithatch/opensim-spawner:latest
```

Then call the API at:

- `http://localhost:8993/`

## Project Links

- Main AI Stack (`opensim-ai-docker`): https://github.com/opensim-stack/opensim-ai-docker
- `opensim-spawner` on GitHub: https://github.com/opensim-stack/opensim-spawner
- Related MCP server (`opensim-metaverse2mcp`):
  - GitHub: https://github.com/opensim-stack/opensim-metaverse2mcp
  - Docker Hub: https://hub.docker.com/repository/docker/bithatch/opensim-metaverse2mcp/general
