ThisBuild / organization := "lv.id.jc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.4"

ThisBuild / description := "Dice Chess webhook bot in Scala: the engine's aggressive search + opening book, compiled to a GraalVM native image for Azure Functions."

// Engine, opening-book, and webhook runtime live in GitHub Packages, which requires authentication
// even for public packages (read:packages scope). GitHub Packages' Maven registry is per-repository,
// so each artifact needs its own resolver entry — but all share the same host, so the credentials block covers all.
ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/rabestro/dicechess-engine-scala"
ThisBuild / resolvers += "GitHub Packages (dicechess-opening-book)" at
  "https://maven.pkg.github.com/rabestro/dicechess-opening-book"
ThisBuild / resolvers += "GitHub Packages (dicechess-bot-runtime)" at
  "https://maven.pkg.github.com/rabestro/dicechess-bot-runtime"

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

val DiceChessOpeningBookVersion = "0.1.0"
val DiceChessBotRuntimeVersion  = "1.0.0"
val MunitVersion                = "1.3.4"

lazy val root = (project in file("."))
  .enablePlugins(NativeImagePlugin)
  .settings(
    name                := "dicechess-bot-azure",
    Compile / mainClass := Some("dicechess.bot.Main"),
    libraryDependencies ++= Seq(
      // Opening book artifact (transitively brings dicechess-engine-scala for game rules)
      "lv.id.jc" %% "dicechess-opening-book" % DiceChessOpeningBookVersion,
      // Plain `%`, not `%%` — a Java artifact, not cross-built per Scala version. Replaces this
      // repo's own Webhook.scala/Main.scala HTTP-server plumbing with the shared, independently
      // tested implementation (HMAC signing, handshake, TurnContext, CustomHandlerServer).
      "lv.id.jc" % "dicechess-bot-runtime" % DiceChessBotRuntimeVersion,
      "org.scalameta" %% "munit" % MunitVersion % Test
    ),
    nativeImageInstalled := true,
    nativeImageOptions ++= List("--no-fallback", "--install-exit-handlers"),
    nativeImageOutput := target.value / "native-image" / "dicechess-bot"
  )
