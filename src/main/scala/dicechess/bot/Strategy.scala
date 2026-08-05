package dicechess.bot

import dicechess.engine.domain.{FenParser, Move}
import dicechess.engine.search.{AggressiveSearch, OpeningBookBot, OpeningBookParser, SearchAlgorithm}
import dicechess.openingbook.InstrumentedBook

import java.nio.file.{Files, Path}

/** The move-choosing brain: the engine's `AggressiveSearch` decorated with the opening book
  * (`InstrumentedBook`). This is the payoff of linking the engine instead of talking to it over the wire — the
  * bot needs nothing but the DFEN from the webhook envelope: the engine parses it (dice pool included, 7th field),
  * enumerates the legal turns itself, consults the book, and evaluates with the aggressive king-hunt heuristics. No
  * `legalMoves` tree from the wire, no fallback fetch of `GET /games/{id}/moves` — the position string is sufficient.
  */
final class Strategy(val bot: SearchAlgorithm):

  /** DFEN in (the envelope's `state.dfen`), UCI micro-move path out. `Nil` = forced pass (the server auto-passes; the
    * webhook answers `{"moves": []}`, which plays nothing — correct and harmless). `Left` = unusable DFEN.
    */
  def chooseMoves(dfen: String): Either[String, List[String]] =
    FenParser.parse(dfen).map { state =>
      bot.findBestMove(state).map(_.moves.map(Strategy.toUci)).getOrElse(Nil)
    }

object Strategy:

  /** UCI for a search-layer `Move` (which has no notation of its own) — the same recipe play-api's `EngineOps` uses,
    * so the strings this bot submits are byte-for-byte what the server's own enumeration produces.
    */
  def toUci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")

  /** Build the aggressive+book strategy from an `opening_book.json` on disk if present, falling back to the
    * bundled `dicechess-opening-book` artifact resource.
    */
  def default(bookPath: Option[Path] = None): Strategy =
    bookPath.filter(p => Files.exists(p)) match
      case Some(path) =>
        OpeningBookParser.parse(Files.readString(path)) match
          case Right(entries) =>
            println(s"[bot] opening book loaded: ${entries.size} entries from file $path")
            new Strategy(OpeningBookBot.decorate(AggressiveSearch, entries))
          case Left(error) =>
            System.err.println(s"[bot] opening book file at $path is malformed ($error) — falling back to bundled book")
            loadBundledBook()
      case None =>
        loadBundledBook()

  def fromBookFile(path: Path): Strategy = default(Some(path))

  private def loadBundledBook(): Strategy =
    InstrumentedBook.loadDefault(AggressiveSearch) match
      case Right(bookBot) =>
        println(s"[bot] bundled opening book loaded (${bookBot.book.size} entries)")
        new Strategy(bookBot)
      case Left(error) =>
        System.err.println(s"[bot] bundled opening book unavailable ($error) — playing bookless")
        new Strategy(AggressiveSearch)
