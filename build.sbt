ThisBuild / organization := "lv.id.jc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"

ThisBuild / description := "Dice Chess webhook bot in Scala: the engine's aggressive search + opening book, compiled to a GraalVM native image for Azure Functions."

// Both the engine and the webhook runtime live in GitHub Packages, which requires
// authentication even for public packages (read:packages scope). GitHub Packages'
// Maven registry is per-repository, so each artifact needs its own resolver entry —
// but both share the same host, so the one credentials block below covers both.
ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/rabestro/dicechess-engine-scala"
ThisBuild / resolvers += "GitHub Packages (dicechess-bot-runtime)" at
  "https://maven.pkg.github.com/rabestro/dicechess-bot-runtime"

// Credentials for that resolver. `credentials` is an sbt *setting*, evaluated on every
// load — even for offline tasks — so we keep it free of network calls: GitHub Packages
// validates only the token (the password) and accepts any non-empty username. CI exports
// GITHUB_TOKEN; locally we read it from the gh CLI, which returns the token from the OS
// keychain without touching the network (works offline; the token never lands in a file).
def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion = "1.6.1"
val DiceChessBotRuntimeVersion = "1.0.0"
val MunitVersion = "1.3.4"

lazy val root = (project in file("."))
  .enablePlugins(NativeImagePlugin)
  .settings(
    name                := "dicechess-bot-azure",
    Compile / mainClass := Some("dicechess.bot.Main"),
    libraryDependencies ++= Seq(
      // The whole point of this starter: the real engine as a dependency — AggressiveSearch,
      // OpeningBookBot, FenParser, TurnGenerator. Pulls circe transitively (OpeningBookParser).
      "lv.id.jc" %% "dicechess-engine-scala" % DiceChessEngineVersion,
      // Plain `%`, not `%%` — a Java artifact, not cross-built per Scala version. Replaces this
      // repo's own Webhook.scala/Main.scala HTTP-server plumbing with the shared, independently
      // tested implementation (HMAC signing, handshake, TurnContext, CustomHandlerServer).
      "lv.id.jc" % "dicechess-bot-runtime" % DiceChessBotRuntimeVersion,
      "org.scalameta" %% "munit" % MunitVersion % Test
    ),
    // native-image comes from the environment (CI: graalvm/setup-graalvm; locally: a GraalVM
    // on PATH). The engine is pure bitboard computation and circe derivation is compile-time,
    // so no reflection configs are needed; --no-fallback makes any regression a build error
    // instead of a silently-degraded image that needs a JVM at runtime.
    nativeImageInstalled := true,
    nativeImageOptions ++= List("--no-fallback", "--install-exit-handlers"),
    nativeImageOutput := target.value / "native-image" / "dicechess-bot"
  )
