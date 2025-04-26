val scala3Version = "3.6.4"

lazy val root = project
  .in(file("."))
  .settings(
    name := "zeroshot image embedder",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq( // += から ++= に変更し、Seq で複数の依存関係を追加
      // OpenAI
      "io.cequence" %% "openai-scala-client" % "1.1.2",
      // Circe (JSON)
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      // os-lib (File I/O, Env Vars)
      "com.lihaoyi" %% "os-lib" % "0.9.3",
      // Scrimage (Image Processing)
      "com.sksamuel.scrimage" % "scrimage-core" % "4.3.1",
      "com.sksamuel.scrimage" % "scrimage-formats-extra" % "4.3.1", // For JPEG, PNG etc.
      // Commons Codec (Base64) - scrimage が Base64 を直接サポートしない場合に備える
      "commons-codec" % "commons-codec" % "1.16.0",
      // Test dependency
      "org.scalameta" %% "munit" % "1.0.0" % Test
    )
  )
