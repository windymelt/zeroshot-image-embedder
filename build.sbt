import sbtassembly.AssemblyPlugin.defaultUniversalScript

val scala3Version = "3.6.4"

lazy val root = project
  .in(file("."))
  .settings(
    name := "zeroshot-image-embedder",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    // アセンブリ設定
    assembly / mainClass := Some("Main"),
    assembly / assemblyJarName := "zeroshot-image-embedder.jar",

    // シェルスクリプトプレペンド設定
    assembly / assemblyPrependShellScript := Some(
      defaultUniversalScript(shebang = false)
    ),

    // マージ戦略（特にAkkaを使用する場合に必要）
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard // module-info.class を破棄
      case PathList("reference.conf") => MergeStrategy.concat
      case "application.conf"         => MergeStrategy.concat
      case x if x.endsWith(".conf")   => MergeStrategy.concat
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    libraryDependencies ++= Seq(
      // OpenAI
      "io.cequence" %% "openai-scala-client" % "1.2.0",
      "io.bullet" %% "borer-core" % "1.10.3",
      // os-lib (File I/O, Env Vars)
      "com.lihaoyi" %% "os-lib" % "0.9.3",
      // Scrimage (Image Processing)
      "com.sksamuel.scrimage" % "scrimage-core" % "4.3.1",
      "com.sksamuel.scrimage" % "scrimage-formats-extra" % "4.3.1", // For JPEG, PNG etc.
      // Test dependency
      "org.scalameta" %% "munit" % "1.0.0" % Test
    )
  )
