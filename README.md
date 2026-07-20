# Dice Chess bot — Scala starter (engine-powered)

A Dice Chess webhook bot in **Scala 3** that links the **real game engine** as a dependency —
[`dicechess-engine-scala`](https://github.com/rabestro/dicechess-engine-scala) — and plays its
**aggressive** king-hunt search behind the exported **opening book**
(`OpeningBookBot.decorate(AggressiveSearch, book)`). Compiled to a **GraalVM native image**, it
runs as an Azure Functions **custom handler**: cold starts in the same league as Node, none of
the JVM's 5–20 s serverless startup pain.

Where the [TypeScript](https://github.com/rabestro/dicechess-bot-typescript) and
[Python](https://github.com/rabestro/dicechess-bot-python) starters are MIT transport shells that
walk the server-provided move tree, this one is the full-strength path: the engine parses the
DFEN from the webhook envelope (dice pool included), enumerates legal turns itself, consults the
book, and evaluates the position. The envelope's position string is all it needs.

## Licensing

**AGPL-3.0**, because it links the AGPL engine. Forks and experiments are welcome — derived bots
stay AGPL. If you want a **closed-source** bot, use the MIT starters instead: the legal moves are
already on the wire, so no engine linkage is ever required — see
[Licensing for Bots](https://rabestro.github.io/dicechess-play-api/licensing/).

## Layout

| Path | Role |
| --- | --- |
| `src/main/scala/dicechess/bot/Strategy.scala` | aggressive + book composition; DFEN in, UCI path out. **Swap the algorithm here.** |
| `src/main/scala/dicechess/bot/Webhook.scala` | Pure delivery logic: HMAC verification (±5 min replay window), handshake nonce echo. |
| `src/main/scala/dicechess/bot/Main.scala` | JDK `HttpServer` custom-handler process (`FUNCTIONS_CUSTOMHANDLER_PORT`). |
| `opening_book.json` | The exported opening book (a file on disk — swap without rebuilding). |
| `host.json` · `webhook/function.json` | Azure Functions custom-handler wiring (`enableForwardingHttpRequest`). |

## Local development

Requires JDK 21+ and sbt; resolving the engine needs a GitHub token with `read:packages`
(`gh auth login` is enough — the build reads `gh auth token`).

```bash
sbt test        # hermetic: signature vector, handshake, legality via the engine, book-hit
sbt run         # serves on :8080; then e.g.:
curl -X POST localhost:8080/api/webhook -d '{"type":"verification","nonce":"x"}'
```

## Deploy to Azure Functions

The binary is **linux-x64** and is built by CI (a macOS/ARM machine cannot produce it locally) —
grab the `dicechess-bot-linux-x64` artifact from the latest [Actions](../../actions) run:

```bash
gh run download --repo <your-fork> --name dicechess-bot-linux-x64
chmod +x dicechess-bot          # the artifact loses the executable bit
```

Create the Function App (**`--runtime custom`**, not node) — same one-time resource setup as the
[TypeScript starter's AZURE.md](https://github.com/rabestro/dicechess-bot-typescript/blob/main/AZURE.md)
(resource group, storage account, provider registration gotchas — all identical):

```bash
az functionapp create \
  --resource-group <rg> \
  --consumption-plan-location <region> \
  --runtime custom --functions-version 4 \
  --name <app-name> --storage-account <storage> --os-type Linux
```

Deploy from the repo root with the binary in place. The `--custom` flag is required: Core Tools
can't auto-detect the language of a custom-handler project (there's no `local.settings.json`
marker) and otherwise refuses with "Can't determine project language from files":

```bash
func azure functionapp publish <app-name> --custom
```

Then the platform-side steps (shown as `curl`; any HTTP client works):

```bash
BASE=https://play-api.jc.id.lv

# 1. Claim a durable identity (registered bots only can webhook + ladder). Token shown ONCE.
curl -X POST "$BASE/bot/register" -H "Content-Type: application/json" \
  -d '{"team":"<team>","name":"<name>"}'

# 2. Register the webhook (the deployed function must already answer — ownership handshake).
#    The response carries the signing secret, shown ONCE.
curl -X POST "$BASE/bot/webhook" -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://<app-name>.azurewebsites.net/api/webhook"}'

# 3. Give the handler its secret (Azure restarts the app automatically).
az functionapp config appsettings set --name <app-name> --resource-group <rg> \
  --settings DICECHESS_WEBHOOK_SECRET=<secret>

# 4. Join the rating ladder — passive from here; watch /bots/<team>/<name> converge.
curl -X POST "$BASE/bot/ladder/join" -H "Authorization: Bearer <token>"
```

Full platform reference: <https://rabestro.github.io/dicechess-play-api/>.

## Why native-image

The webhook contract is a synchronous request/response with a hard budget
(`min(server cap ≈ 15 s, remaining clock)`) and **single-attempt delivery** — a JVM cold start of
5–20 s is a real competitive liability there. The native binary starts in tens of milliseconds;
the engine is pure bitboard computation with no reflection, so the image builds with
`--no-fallback` and no configs. Move computation itself (aggressive is a one-ply evaluation over
the legal turns) takes milliseconds — no time management needed.
