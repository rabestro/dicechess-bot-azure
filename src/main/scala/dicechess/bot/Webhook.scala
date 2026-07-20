package dicechess.bot

import io.circe.Json
import io.circe.parser.parse

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Webhook delivery logic, pure of any HTTP-server concern — the Scala sibling of the TypeScript/Python starters'
  * `webhook` modules, speaking the same contract the play platform documents:
  *
  *   - every delivery carries `X-DiceChess-Timestamp` and `X-DiceChess-Signature: HMAC-SHA256(secret, "<ts>.<body>")`
  *     (hex); verify before trusting, reject a stale timestamp (±5 minutes);
  *   - `{"type":"verification","nonce":…}` is the registration ownership handshake — echoed unconditionally, because
  *     the secret is only disclosed after it succeeds (leaking the nonce is harmless; no game action follows);
  *   - `{"type":"yourTurn", "state":{"dfen":…}}` → `{"moves":[…]}` — the HTTP response body IS the move.
  */
object Webhook:

  /** Lower-cased header names (the transport hands us a lower-cased map). */
  val SignatureHeader = "x-dicechess-signature"
  val TimestampHeader = "x-dicechess-timestamp"

  private val MaxSkewSeconds = 300L // ±5 minutes — the documented replay window

  final case class Response(status: Int, body: Json)

  /** Hex HMAC-SHA256 of `"<timestampEpochSeconds>.<body>"` under `secret` — the signature scheme shared by every
    * starter and the server (see the ecosystem-wide test vector in `WebhookSuite`).
    */
  def sign(secret: String, timestampEpochSeconds: Long, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(s"$timestampEpochSeconds.$body".getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** True iff `signature` is the fresh, genuine MAC of `rawBody`. Constant-time comparison via
    * `MessageDigest.isEqual`; `now` is a parameter so tests are deterministic.
    */
  def verifySignature(
      secret: String,
      timestamp: Option[String],
      rawBody: String,
      signature: Option[String],
      nowEpochSeconds: Long
  ): Boolean =
    (timestamp, signature) match
      case (Some(ts), Some(sig)) =>
        ts.toLongOption.exists { t =>
          math.abs(nowEpochSeconds - t) <= MaxSkewSeconds &&
          MessageDigest.isEqual(sign(secret, t, rawBody).getBytes(UTF_8), sig.getBytes(UTF_8))
        }
      case _ => false

  /** Turn one webhook POST into a status + JSON body. `headers` keys must be lower-cased. */
  def handleDelivery(
      headers: Map[String, String],
      rawBody: String,
      secret: String,
      strategy: Strategy,
      nowEpochSeconds: Long = System.currentTimeMillis() / 1000
  ): Response =
    parse(rawBody) match
      case Left(_) => Response(400, error("malformed JSON"))
      case Right(json) =>
        val cursor = json.hcursor
        cursor.get[String]("type").toOption match
          case Some("verification") =>
            Response(200, Json.obj("nonce" -> Json.fromString(cursor.get[String]("nonce").getOrElse(""))))
          case _ =>
            if !verifySignature(secret, headers.get(TimestampHeader), rawBody, headers.get(SignatureHeader), nowEpochSeconds)
            then Response(401, error("invalid signature"))
            else
              cursor.downField("state").get[String]("dfen").toOption match
                case None => Response(400, error("no state.dfen in envelope"))
                case Some(dfen) =>
                  strategy.chooseMoves(dfen) match
                    case Left(reason) => Response(400, error(s"unusable dfen: $reason"))
                    case Right(moves) =>
                      Response(200, Json.obj("moves" -> Json.fromValues(moves.map(Json.fromString))))

  private def error(message: String): Json = Json.obj("error" -> Json.fromString(message))
