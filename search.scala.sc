//> using scala 3.6
//> using dep com.softwaremill.sttp.client4::core:4.0.3
//> using dep io.circe::circe-generic:0.14.13
//> using dep io.circe::circe-core:0.14.13
//> using dep io.circe::circe-parser:0.14.13
//> using dep "io.cequence::openai-scala-client:1.2.0"

import sttp.client4.quick.*
import io.circe.syntax.*
import io.circe.*
import io.circe.Json.*
import io.circe.parser.*
import scala.util.Try
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem

import io.cequence.openaiscala.domain._
import io.cequence.openaiscala.service.OpenAIServiceFactory
import io.cequence.openaiscala.domain.settings.CreateEmbeddingsSettings
import io.cequence.openaiscala.service.impl.Param.encoding_format
import io.cequence.openaiscala.domain.settings.EmbeddingsEncodingFormat

val MeilisearchUrl = "http://localhost:7700"
val EmbeddingModelId = "text-embedding-3-small"
val ApiTimeout = 60.seconds

val searchQuery = args(0)
println(s"Search query: $searchQuery")

val apiToken = sys.env.get("OPENAI_TOKEN").getOrElse {
  System.err.println("環境変数 OPENAI_TOKEN が設定されていません")
  System.exit(1)
  "" // コンパイル用（実際には到達しない）
}

implicit val system: ActorSystem = ActorSystem("OpenAIClientSystem")

val vec =
  try {
    val openAIService = OpenAIServiceFactory(apiToken)
    try {
      val embeddingSettings = CreateEmbeddingsSettings(
        model = EmbeddingModelId,
        encoding_format = Some(EmbeddingsEncodingFormat.float)
      )

      val embeddingFuture =
        openAIService.createEmbeddings(Seq(searchQuery), embeddingSettings)
      val embeddingResponse = Await.result(embeddingFuture, ApiTimeout)

      val embeddingVector = embeddingResponse.data.headOption
        .map(_.embedding)
        .getOrElse(
          throw new RuntimeException("Embedding API から有効なベクトルを取得できませんでした")
        )

      embeddingVector
    } catch {
      case e: Exception =>
        System.err.println(s"APIの呼び出し中にエラーが発生しました: ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1)
    } finally {
      // OpenAIサービスのクローズ
      openAIService.close()
    }
  } finally {
    // ActorSystemの終了
    system.terminate()
  }

println(vec.take(10).mkString(", "))

val bodyJson = JsonObject.fromMap(
  Map(
    "vector" -> Json.fromValues(
      vec.map(Json.fromDouble(_).get)
    ),
    "hybrid" -> JsonObject
      .fromMap(
        Map(
          "embedder" -> Json.fromString("photoEmbedder")
        )
      )
      .toJson
  )
)
val body = bodyJson.asJson.noSpaces
val res = quickRequest
  .post(
    uri"$MeilisearchUrl/indexes/photo_index/search"
  )
  .body(body)
  .header("Content-Type", "application/json")
  .send()

println(
  parse(res.body).right.get.hcursor
    .downField("hits")
    .downArray
    .downField("filePath")
    .as[String]
    .right
    .get
)
