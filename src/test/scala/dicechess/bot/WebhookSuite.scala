package dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.TurnGenerator
import io.circe.parser.parse

/** The delivery contract, hermetically. The signature vector is the ecosystem-wide one — the same bytes are asserted
  * in play-api's `WebhookSecuritySuite` and the TypeScript/Python starters, so all four implementations provably speak
  * one scheme.
  */
class WebhookSuite extends munit.FunSuite:

  private val Secret   = "test-webhook-secret"
  private val Now      = 1752750000L
  private val strategy = new Strategy(dicechess.engine.search.AggressiveSearch)

  private val initialNbk = FenParser.InitialPosition + " NBK"

  test("sign matches the ecosystem-wide HMAC-SHA256 vector"):
    assertEquals(
      Webhook.sign(Secret, Now, """{"hello":true}"""),
      "5f4fbf105bab278dc6205788389e09884bd554b1f866ca11ccc9ce97ddd9b3f6"
    )

  test("verification handshake echoes the nonce without a signature"):
    val response = Webhook.handleDelivery(Map.empty, """{"type":"verification","nonce":"abc123"}""", Secret, strategy)
    assertEquals(response.status, 200)
    assertEquals(response.body.hcursor.get[String]("nonce"), Right("abc123"))

  test("a signed turn returns a path the engine itself considers legal"):
    val body = s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"dfen":"$initialNbk"}}"""
    val headers = Map(
      Webhook.TimestampHeader -> Now.toString,
      Webhook.SignatureHeader -> Webhook.sign(Secret, Now, body)
    )
    val response = Webhook.handleDelivery(headers, body, Secret, strategy, nowEpochSeconds = Now)
    assertEquals(response.status, 200)
    val moves = response.body.hcursor.get[List[String]]("moves").toOption.get
    assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
    val state      = FenParser.parse(initialNbk).toOption.get
    val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
    assert(legalPaths.contains(moves), s"$moves must be one of the engine's own legal paths")

  test("a tampered or missing signature is rejected with 401"):
    val body    = s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"dfen":"$initialNbk"}}"""
    val badSig  = Map(Webhook.TimestampHeader -> Now.toString, Webhook.SignatureHeader -> "deadbeef")
    val missing = Map.empty[String, String]
    assertEquals(Webhook.handleDelivery(badSig, body, Secret, strategy, nowEpochSeconds = Now).status, 401)
    assertEquals(Webhook.handleDelivery(missing, body, Secret, strategy, nowEpochSeconds = Now).status, 401)

  test("a stale timestamp is rejected even with a genuine signature (replay guard)"):
    val body    = s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"dfen":"$initialNbk"}}"""
    val staleTs = Now - 3600
    val headers = Map(
      Webhook.TimestampHeader -> staleTs.toString,
      Webhook.SignatureHeader -> Webhook.sign(Secret, staleTs, body)
    )
    assertEquals(Webhook.handleDelivery(headers, body, Secret, strategy, nowEpochSeconds = Now).status, 401)

  test("garbage JSON and a missing dfen are 400, never an exception"):
    assertEquals(Webhook.handleDelivery(Map.empty, "not json at all", Secret, strategy).status, 400)
    val noDfen  = """{"type":"yourTurn","gameId":"g1","seat":"White","state":{}}"""
    val headers = Map(
      Webhook.TimestampHeader -> Now.toString,
      Webhook.SignatureHeader -> Webhook.sign(Secret, Now, noDfen)
    )
    assertEquals(Webhook.handleDelivery(headers, noDfen, Secret, strategy, nowEpochSeconds = Now).status, 400)

  test("end to end over real HTTP: handshake and a signed turn against the JDK server"):
    val server = Main.start(port = 0, secret = Secret, strategy = strategy)
    try
      val base   = s"http://127.0.0.1:${server.getAddress.getPort}/api/webhook"
      val client = java.net.http.HttpClient.newHttpClient()

      def post(body: String, headers: Map[String, String]): java.net.http.HttpResponse[String] =
        val builder = java.net.http.HttpRequest
          .newBuilder(java.net.URI.create(base))
          .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        headers.foreach((k, v) => builder.header(k, v))
        client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())

      val handshake = post("""{"type":"verification","nonce":"live-1"}""", Map.empty)
      assertEquals(handshake.statusCode(), 200)
      assertEquals(parse(handshake.body()).toOption.get.hcursor.get[String]("nonce"), Right("live-1"))

      val body = s"""{"type":"yourTurn","gameId":"g1","seat":"White","state":{"dfen":"$initialNbk"}}"""
      val ts   = System.currentTimeMillis() / 1000
      val turn = post(
        body,
        Map(Webhook.TimestampHeader -> ts.toString, Webhook.SignatureHeader -> Webhook.sign(Secret, ts, body))
      )
      assertEquals(turn.statusCode(), 200)
      assert(parse(turn.body()).toOption.get.hcursor.get[List[String]]("moves").toOption.get.nonEmpty)
    finally server.stop(0)
