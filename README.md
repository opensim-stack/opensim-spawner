# opensim-spawner

[![Docker Hub](https://img.shields.io/badge/Docker%20Hub-bithatch%2Fopensim--spawner-latest?logo=docker&logoColor=white)](https://hub.docker.com/repository/docker/bithatch/opensim-spawner)

HTTP API service that creates and coordinates OpenSim simulators and bot containers from a strict profilse.

*This is part of the [opensim-stack](https://opensim-stack.github.io/) and is intended to be used in conjunction with other parts of the stack. See [Docs](https://opensim-stack.github.io/docs/index.html) for full details.*


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

## Default container volumes:

- `/config`
- `/data`
- `/workspace`


## Image Names

| Name | Default Value |
| `OPENSIM_OPENCODE_IMAGE` | `bithatch/opensim-opencode:latest` |
| `OPENSIM_METAVERSE2MCP_IMAGE` | `bithatch/opensim-metaverse2mcp:latest` |
| `OPENSIM_SIMULATOR_IMAGE` | `bithatch/opensim-simulator:latest` |

## Main environment variables

| Name | Default Value |
| `COMPOSE_PROJECT_NAME`| `opensim-ai` |
| `OPENSIM_SPAWNER_HTTP_HOST`| `0.0.0.0` |
| `OPENSIM_SPAWNER_HTTP_PORT`| `8993` |
| `OPENSIM_SPAWNER_TOKEN` |  |
| `OPENSIM_SPAWNER_FIRST_PORT` | `9000` |
| `OPENSIM_SPAWNER_LAST_PORT` | `9015` |
| `OPENSIM_SPAWNER_MAX_BOTS` | `10` |
| `OPENSIM_CONSOLE_USER` | |
| `OPENSIM_CONSOLE_PASS` | |
| `OPENSIM_CREATE_BOT_USER` | true |
| `OPENSIM_BOT_FIRST` | Bot |
| `OPENSIM_BOT_LAST` | User |
| `OPENSIM_BOT_EMAIL` | bot@localhost |
| `OPENSIM_RESTART_POLICY` | `unless-stopped` |
| `OPENSIM_PULL_POLICY` | `ifnotpresent` |
| `BOT_APPEARNCE` | `Cube Bot` |
| `BOT_GENDER` | `neutral` |

## Environment Variables Passed To `simulator`

| Name | Default Value |
| `MARIADB_HOST` | `mariadb` |
| `MARIADB_DATABASE` | `opensim` |
| `MARIADB_USER` | `opensim` |
| `MARIADB_PASSWORD` | `opensimpassword` |
| `OPENSIM_WEBRTC_VOICE_ENABLED` | `true` |
| `OPENSIM_JANUS_PUBLIC_HOST` | `$OPENSIM_HOSTNAME` |
| `JANUS_HTTP_PORT` | `14223` |
| `JANUS_HTTP_BASEPATH` | `/voice` |
| `JANUS_API_TOKEN` | `` |
| `JANUS_HTTP_ADMIN_PORT` | `14225` |
| `JANUS_HTTP_ADMIN_BASEPATH` | `/voiceAdmin` |
| `JANUS_ADMIN_TOKEN` | `` |
| `OPENSIM_HOSTNAME` | `opensim` |
| `OPENSIM_ESTATE_NAME` | `Botland` |
| `OPENSIM_USER_FIRST` | `Bot` |
| `OPENSIM_USER_LAST` | `Handler` |
| `OPENSIM_USER_PASSWORD` | `changeme` |
| `OPENSIM_ROBUST_PUBLIC_PORT` | `8002` |
| `OPENSIM_ROBUST_PRIVATE_PORT` | `8003` |
| `OPENSIMGRID_NAME` | `Bot Grid` |
| `OPENSIMGRID_NICK` | `botgrid` |
| `OPENSIM_WELCOME_MESSAGE` | `Welcome to ${OPENSIMGRID_NAME:-Bot Grid}!` |

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
| `VOICE_ROUTING_ENABLED` | `true` |
| `VOICE_BACKEND` | `webrtc` |
| `PIPER_SCHEME` | `http` |
| `PIPER_HOST` | `opensim-ai-piper-1` |
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

