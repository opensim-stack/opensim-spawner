# opensim-spawner

[![Docker Hub](https://img.shields.io/badge/Docker%20Hub-bithatch%2Fopensim--spawner-2496ED?logo=docker&logoColor=white)](https://hub.docker.com/repository/docker/bithatch/opensim-spawner)

HTTP API service that creates and coordinates OpenSim bot-side containers from a strict bot-level profile.

*This is part of the [opensim-stack](https://opensim-stack.github.io/) and is intended to be used in conjunction with other parts of the stack. See [Docs](https://opensim-stack.github.io/docs/index.html) for full details.*

## What is implemented

- Spring Web HTTP API:
  - `GET /api/bot` lists bots
  - `GET /api/bot/{first}/{last}` returns current associated container status JSON including `parent` and `children`
  - `POST /api/bot/{first}/{last}` creates bot from form or multipart fields (requires `parent`, blank allowed temporarily)
  - `PATCH /api/bot/{first}/{last}` performs bot action(s), currently `action=start|stop|restart`
  - `DELETE /api/bot/{first}/{last}` shuts down bot containers and deletes container volumes
- Web UI:
  - `/` -> `/index.html` -> `/ui/index.html` -> `/ui/bots.html`
  - UI pages are under `/ui/*` and require login at `/ui/login.html`
  - UI login validates `OPENSIM_CONSOLE_USER` and `OPENSIM_CONSOLE_PASS`
- Optional bearer auth via `OPENSIM_SPAWNER_TOKEN`
- Bot state persistence in `/data/<first>-<last>.json`
- Startup dynamic-port resume from existing state files
- Profile loading from `/config/bot-levels.json` or bundled `default-bot-levels.json`
- Template substitution for `%bot.*`, `%ports.*`, `%env.*`
- Docker orchestration using Docker Java SDK
- OpenSim user creation through copied `OpensimRESTConsole`

## Build

```bash
mvn clean package
```

## Build and test

```bash
mvn test
```

## Run locally

```bash
OPENSIM_SPAWNER_HTTP_HOST=127.0.0.1 OPENSIM_SPAWNER_HTTP_PORT=8993 mvn spring-boot:run
```

## Main environment variables

| Name | Default Value |
| `OPENSIM_SPAWNER_HTTP_HOST`| `0.0.0.0` |
| `OPENSIM_SPAWNER_HTTP_PORT`| `8993` |
| `OPENSIM_SPAWNER_TOKEN` |  |
| `OPENSIM_SPAWNER_FIRST_PORT` | `8700` |
| `OPENSIM_SPAWNER_MAX_BOTS` | `10` |
| `OPENSIM_CONSOLE_URL` | |
| `OPENSIM_CONSOLE_USER` | |
| `OPENSIM_CONSOLE_PASS` | |
| `OPENSIM_CREATE_BOT_USER` | true |
| `OPENSIM_LOGIN_FIRSTNAME` | Bot |
| `OPENSIM_LOGIN_LASTNAME` | User |
| `OPENSIM_LOGIN_EMAIL` | bot@localhost |
| `OPENSIM_LOGIN_MODEL` | Ruth |
| `OPENSIM_RESTART_POLICY` | `unless-stopped` |
| `OPENSIM_PULL_POLICY` | `ifnotpresent` |
| `BOT_APPEARNCE` | `Cube Bot` |
| `BOT_GENDER` | `neutral` |


## Environment Variables Passed To `opencode`

| Name | Default Value |
| `OPENCODE_HOST` | `0.0.0.0` |
| `OPENCODE_PORT` | `8998` |
| `OPENCODE_SERVER_USERNAME` | `<first>-<last>` |
| `OPENCODE_SERVER_PASSWORD` | `<bot-password>` |

## Environment Variables Passed To `metaverse2mcp`

| Name | Default Value |
| `OPENSIM_LOGIN_START` | `last` |
| `OPENSIM_LOGIN_URI` | `http://opensim:9000` |
| `SPAWNER_HOST` | `opensim-spawner` |
| `OPENSIM_METAVERSE2MCP_IMAGE` | |
| `OPENSIM_OPENCODE_IMAGE` | |
| `OPENSIM_BOT_HANDLER_FIRSTNAME` | `$OPENSIM_ESTATE_OWNER_FIRST` |
| `OPENSIM_BOT_HANDLER_LASTNAME` | `$OPENSIM_ESTATE_OWNER_LAST` |
| `VOICE_ROUTING_ENABLED` | `true` |
| `VOICE_BACKEND` | `webrtc` |
| `PIPER_SCHEME` | `http` |
| `PIPER_HOST` | `opensim-piper` |
| `PIPER_PORT` | `8995` |
| `PIPER_TTS_PATH` | `/tts` |
| `PIPER_VOICES_PATH` | `/voices` |
| `PIPER_REQUEST_TIMEOUT_SECONDS` | `60` |
| `PIPER_DEFAULT_VOICE` | `en_US-lessac-medium` |
| `METAVERSE_MCP_TRANSPORT` | `http` |
| `METAVERSE_MCP_HOST` | `0.0.0.0` |
| `METAVERSE_MCP_PORT` | `8999` |
| `METAVERSE_MCP_HTTP_ENDPOINT` | `/mcp` |
| `METAVERSE_MCP_HTTP_DISALLOW_DELETE` | `false` |
| `METAVERSE_MCP_DIAGNOSTICS` | `false` |
| `CONSOLE_MCP_PORT` | 8997 |
| `CONSOLE_MCP_HTTP_ENDPOINT` | /mcp |
| `CONSOLE_MCP_HTTP_BEARER_TOKEN` | |
| `OPENCODE_PORT` | `8998` |
| `OPENCODE_HOST` | `opensim-opencode-<first>-<last>` |
| `OPENCODE_INITIAL_PROVIDER` | `` |
| `OPENCODE_INITIAL_MODEL` | `` |
| `OPENCODE_DEFAULT_PROMPT_PATH` | `/workspace/bots/<first>-<last>/AGENTS.md` |
| `PROMPT_HANDLING_ENABLED` | true |
| `PROMPT_PROJECT_AGENTS_ENABLED` | `true` |
| `PROMPT_PROJECT_AGENTS_FILE` | /app/AGENTS.md |
| `PROMPT_NOTECARD_REQUIRE_HANDLER` | `true` |


## API examples (curl)

```bash
BASE_URL="http://127.0.0.1:8993"
BOT_API_BASE="${BASE_URL}/api/bot"
TOKEN=""
AUTH_HEADER=()
if [ -n "$TOKEN" ]; then AUTH_HEADER=(-H "Authorization: Bearer $TOKEN"); fi
```

List bots:

```bash
curl -sS "${AUTH_HEADER[@]}" "${BOT_API_BASE}"
```

Query one bot's container status:

```bash
curl -sS "${AUTH_HEADER[@]}" "${BOT_API_BASE}/Alice/Bot"
```

Create a bot:

```bash
curl -sS -X POST "${AUTH_HEADER[@]}" "${BOT_API_BASE}/Alice/Bot" \
  -d "level=actor" \
  -d "parent=Governor Bot" \
  -d "email=alice.bot@localhost" \
  -d "model=Ruth" \
  -d "EXAMPLE_FIELD=example-value"
```

If omitted, `email` defaults to `<first>.<last>@localhost` and `model` defaults to `Ruth`.
`parent` is the full bot name (`<first> <last>`). For debugging, blank parent is currently allowed.
When parent is non-blank, parent level must be lower-numbered than child level (for example `GOVERNOR` can create `BUILDER`/`ACTOR`, `BUILDER` can create `ACTOR`).

Delete a bot (container shutdown + volume delete):

```bash
curl -sS -X DELETE "${AUTH_HEADER[@]}" "${BOT_API_BASE}/Alice/Bot"
```

Start a bot (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${BOT_API_BASE}/Alice/Bot" \
  -d "action=start"
```

Stop a bot (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${BOT_API_BASE}/Alice/Bot" \
  -d "action=stop"
```

Restart a bot (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${BOT_API_BASE}/Alice/Bot" \
  -d "action=restart"
```

## Docker (multiarch Java runtime image)

- `Dockerfile` (multi-stage Maven build, JVM runtime image)
- `docker/entrypoint.sh` (standard Java startup wrapper)

Default container volumes:

- `/config`
- `/data`
- `/workspace`

### Build local image

```bash
docker build -t opensim-spawner:local .
```

### Run local image

```bash
docker run --rm \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e OPENSIM_SPAWNER_HTTP_HOST=0.0.0.0 \
  -e OPENSIM_SPAWNER_HTTP_PORT=8993 \
  -e OPENSIM_SPAWNER_TOKEN= \
  -e OPENSIM_SPAWNER_FIRST_PORT=12345 \
  -e OPENSIM_CONSOLE_URL=http://host.docker.internal:9000 \
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

### Build and publish multiarch image

Create/use a buildx builder once:

```bash
docker buildx create --name multiarch --use
docker buildx inspect --bootstrap
```

Build and push Linux AMD64 + ARM64:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t bithatch/opensim-spawner:latest \
  -t bithatch/opensim-spawner:$(date +%Y%m%d) \
  --push \
  .
```
