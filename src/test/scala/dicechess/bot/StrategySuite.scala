package dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.{AggressiveSearch, OpeningBook, OpeningBookBot, TurnGenerator}

import java.nio.file.Path

/** The brain, hermetically: legal play from a bare DFEN, book-hit precedence, graceful degradation, and the integrity
  * of the bundled opening book resource.
  */
class StrategySuite extends munit.FunSuite:

  private val initialNbk = FenParser.InitialPosition + " NBK"

  test("aggressive play from a bare DFEN yields one of the engine's own legal paths"):
    val strategy = new Strategy(AggressiveSearch)
    val moves    = strategy.chooseMoves(initialNbk).toOption.get
    val state      = FenParser.parse(initialNbk).toOption.get
    val legalPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(Strategy.toUci))
    assert(legalPaths.contains(moves), s"$moves must be a legal full turn")

  test("a booked position plays the booked continuation, not the search's choice"):
    val state = FenParser.parse(initialNbk).toOption.get
    val key   = OpeningBook.key(state).getOrElse(fail("a rolled position must have a book key"))
    // Book a real legal path so the decorator can realise it.
    val booked   = TurnGenerator.generateAllLegalTurnPaths(state).head.map(Strategy.toUci)
    val strategy = new Strategy(OpeningBookBot.decorate(AggressiveSearch, Map(key -> booked.mkString(","))))
    val moves    = strategy.chooseMoves(initialNbk).toOption.get
    assertEquals(moves.sorted, booked.sorted, "the booked turn must win (matched by move multiset)")

  test("an unusable DFEN is an error value, not an exception"):
    assert(new Strategy(AggressiveSearch).chooseMoves("this is not a dfen").isLeft)

  test("the bundled opening book from dicechess-opening-book parses and is non-trivial"):
    val book = dicechess.openingbook.OpeningBookResource.load().toOption.get
    assert(book.sizeIs > 100, s"expected the real exported book, got ${book.size} entries")

  test("fromBookFile survives a missing book (bundled/bookless aggressive still plays)"):
    val strategy = Strategy.fromBookFile(Path.of("no-such-file.json"))
    assert(strategy.chooseMoves(initialNbk).toOption.get.nonEmpty)
