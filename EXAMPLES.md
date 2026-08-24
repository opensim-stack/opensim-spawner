# API Usage Examples

These examples expect you have `curl` installed.


```bash
BASE_URL="http://127.0.0.1:8993"
API_BASE="${BASE_URL}/api"
TOKEN=""
AUTH_HEADER=()
if [ -n "$TOKEN" ]; then AUTH_HEADER=(-H "Authorization: Bearer $TOKEN"); fi
```

## Simulators

List simulators:

```bash
curl -sS "${AUTH_HEADER[@]}" "${API_BASE}/simulator"
```


Query one simulator's container status:

```bash
curl -sS "${AUTH_HEADER[@]}" "${API_BASE}/simulator/Botland"
```

Create a [Robust](http://opensimulator.org/wiki/ROBUST) simulator:

```bash
curl -sS -X POST "${AUTH_HEADER[@]}" "${API_BASE}/simulator/Botland" \
  -d "level=robust" \
  -d "ownerFirst=Bot" \
  -d "ownerLast=Handler" \
  -d "EXAMPLE_FIELD=example-value"
```

If omitted, `email` defaults to `<first>.<last>@localhost`.

Delete a simulator (container shutdown + volume delete):

```bash
curl -sS -X DELETE "${AUTH_HEADER[@]}" "${API_BASE}/simulator/Botland"
```

Start a simulator (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${API_BASE}/simulator/Botland" \
  -d "action=start"
```

Stop a simulator (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${API_BASE}/simulator/Botland" \
  -d "action=stop"
```

Restart a simulator (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${API_BASE}/simulator/Botland" \
  -d "action=restart"
```


## Bots

List bots:

```bash
curl -sS "${AUTH_HEADER[@]}" "${API_BASE}/bot"
```

Query one bot's container status:

```bash
curl -sS "${AUTH_HEADER[@]}" "${API_BASE}/bot/Alice/Bot"
```

Create a bot:

```bash
curl -sS -X POST "${AUTH_HEADER[@]}" "${API_BASE}/bot/Alice/Bot" \
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
curl -sS -X DELETE "${AUTH_HEADER[@]}" "${API_BASE}/bot/Alice/Bot"
```

Start a bot (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${API_BASE}/bot/Alice/Bot" \
  -d "action=start"
```

Stop a bot (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${API_BASE}/bot/Alice/Bot" \
  -d "action=stop"
```

Restart a bot (all tracked containers):

```bash
curl -sS -X PATCH "${AUTH_HEADER[@]}" "${API_BASE}/bot/Alice/Bot" \
  -d "action=restart"
```
