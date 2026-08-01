# Dice Chess bot — aggressive + book (Scala, engine-powered)

[![CI](https://github.com/rabestro/dicechess-bot-azure/actions/workflows/ci.yml/badge.svg)](https://github.com/rabestro/dicechess-bot-azure/actions/workflows/ci.yml)
[![Play Live](https://img.shields.io/badge/Play-Live-success)](https://play.jc.id.lv/)
[![Leaderboard](https://img.shields.io/badge/Ladder-Leaderboard-1E90FF)](https://play.jc.id.lv/leaderboard)
[![Engine](https://img.shields.io/badge/Engine-dicechess--engine--scala-8A2BE2)](https://github.com/rabestro/dicechess-engine-scala)
[![Bot API](https://img.shields.io/badge/Docs-Bot%20API-orange)](https://bots.jc.id.lv/)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

The live [`azure/scala-aggressive-book`](https://play.jc.id.lv/leaderboard) ladder bot: a Dice Chess webhook bot in **Scala 3** that links
the **real game engine** as a dependency —
[`dicechess-engine-scala`](https://github.com/rabestro/dicechess-engine-scala) — and plays its
**aggressive** king-hunt search behind the exported **opening book**
(`OpeningBookBot.decorate(AggressiveSearch, book)`). Compiled to a **GraalVM native image**, it
runs as an Azure Functions **custom handler**: cold starts in the same league as Node, none of
the JVM's 5–20 s serverless startup pain.

Built from [`dicechess-bot-scala`](https://github.com/rabestro/dicechess-bot-scala) — that
repo is the minimal, no-engine, MIT starter (swap its `Strategy.scala` for your own algorithm);
this one exists because linking the real engine needs an actual dependency and a licensing
choice (see below), which a "maximally simple" template shouldn't carry by default. Start here
directly if you specifically want the engine already wired in.

## Licensing

**AGPL-3.0**, because it links the AGPL engine — this is the trade-off that
[`dicechess-bot-scala`](https://github.com/rabestro/dicechess-bot-scala) deliberately avoids.
Forks and experiments are welcome — derived bots stay AGPL. If you want a **closed-source** bot,
fork the MIT template instead: the legal moves are already on the wire, so no engine linkage is
ever required — see [Licensing for Bots](https://bots.jc.id.lv/licensing/).

## Layout

| Path | Role |
| --- | --- |
| `src/main/scala/dicechess/bot/Strategy.scala` | aggressive + book composition; DFEN in, UCI path out. **Swap the algorithm here.** |
| `src/main/scala/dicechess/bot/Main.scala` | Wires `Strategy` into [`dicechess-bot-runtime`](https://github.com/rabestro/dicechess-bot-runtime)'s `WebhookHandler`/`CustomHandlerServer` — a Java dependency, not this repo's own code. |
| `opening_book.json` | The exported opening book (a file on disk — swap without rebuilding). |
| `host.json` · `webhook/function.json` | Azure Functions custom-handler wiring (`enableForwardingHttpRequest`). |

HMAC verification, the ownership handshake, and the JDK `HttpServer` itself are no longer this
repo's code — they're [`dicechess-bot-runtime`](https://github.com/rabestro/dicechess-bot-runtime)
(`lv.id.jc:dicechess-bot-runtime`), the same dependency a Java or Kotlin bot would use. `Main.scala`
is the entire integration: adapt `Strategy.chooseMoves` to the library's
`Function<TurnContext, List<String>>` shape and start the server.

## Local development

Requires JDK 21+ and sbt; resolving the engine needs a GitHub token with `read:packages`
(`gh auth login` is enough — the build reads `gh auth token`).

```bash
sbt test        # hermetic: legality via the engine, book-hit, one real-HTTP round trip through the library
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

Full platform reference: <https://bots.jc.id.lv/>.

## Why native-image

The webhook contract is a synchronous request/response with a hard budget
(`min(server cap ≈ 15 s, remaining clock)`) and **single-attempt delivery** — a JVM cold start of
5–20 s is a real competitive liability there. The native binary starts in tens of milliseconds;
the engine is pure bitboard computation with no reflection, so the image builds with
`--no-fallback` and no configs. Move computation itself (aggressive is a one-ply evaluation over
the legal turns) takes milliseconds — no time management needed.
