package dicechess.bot

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*

/** The Azure Functions custom-handler process: a plain JDK `HttpServer` (zero extra dependencies, trivially
  * native-image-safe) listening on `FUNCTIONS_CUSTOMHANDLER_PORT`. With `enableForwardingHttpRequest: true` in
  * host.json, the Functions host forwards the original HTTP request as-is — raw body and headers included, which is
  * exactly what HMAC-over-raw-body verification needs — and relays this process's HTTP response back to the caller.
  *
  * Configuration (App Settings on Azure, plain env vars locally):
  *   - `DICECHESS_WEBHOOK_SECRET` — the per-bot signing key from webhook registration. Absent, only the registration
  *     handshake succeeds (deliberate: registration happens before the secret exists — deploy → register → set secret).
  *   - `DICECHESS_BOOK_PATH` — opening-book JSON, default `opening_book.json` in the package root. A file on disk, not
  *     a baked-in resource: swap the book without rebuilding the native image.
  */
object Main:

  def main(args: Array[String]): Unit =
    val port   = sys.env.get("FUNCTIONS_CUSTOMHANDLER_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val secret = sys.env.getOrElse("DICECHESS_WEBHOOK_SECRET", "")
    if secret.isEmpty then
      System.err.println("[bot] DICECHESS_WEBHOOK_SECRET is not set — only the verification handshake will succeed")
    val strategy = Strategy.fromBookFile(Path.of(sys.env.getOrElse("DICECHESS_BOOK_PATH", "opening_book.json")))
    start(port, secret, strategy)
    println(s"[bot] aggressive+book custom handler listening on :$port")
    Thread.currentThread().join() // serve until the host stops the process

  /** Start the server (exposed for the end-to-end test; port 0 = ephemeral). */
  def start(port: Int, secret: String, strategy: Strategy): HttpServer =
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/", (exchange: HttpExchange) => handle(exchange, secret, strategy))
    server.setExecutor(Executors.newFixedThreadPool(4))
    server.start()
    server

  private def handle(exchange: HttpExchange, secret: String, strategy: Strategy): Unit =
    try
      val response =
        if exchange.getRequestMethod.equalsIgnoreCase("POST") then
          val body    = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
          val headers = exchange.getRequestHeaders.entrySet().asScala
            .map(e => e.getKey.toLowerCase -> e.getValue.get(0))
            .toMap
          try Webhook.handleDelivery(headers, body, secret, strategy)
          catch // never 500 the handler — an unanswered turn is the clock's business, same as a polling bot going quiet
            case e: Exception =>
              System.err.println(s"[bot] delivery error: ${e.getMessage}")
              Webhook.Response(400, Json.obj("error" -> Json.fromString("bad request")))
        else Webhook.Response(200, Json.obj("status" -> Json.fromString("ok"))) // GET = health/keep-warm probe
      val bytes = response.body.noSpaces.getBytes(UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(response.status, bytes.length)
      exchange.getResponseBody.write(bytes)
    finally exchange.close()
