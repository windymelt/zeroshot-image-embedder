import scala.util.{Try, Success, Failure}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem

import io.cequence.openaiscala.domain._
import io.cequence.openaiscala.service.OpenAIServiceFactory
import io.cequence.openaiscala.domain.settings.CreateEmbeddingsSettings
import io.cequence.openaiscala.service.impl.Param.encoding_format
import io.cequence.openaiscala.domain.settings.EmbeddingsEncodingFormat
import io.cequence.openaiscala.domain.responsesapi.CreateModelResponseSettings
import io.cequence.openaiscala.domain.responsesapi.{Inputs, Input}
import io.cequence.openaiscala.domain.responsesapi.InputMessageContent
import io.cequence.openaiscala.domain.ChatRole

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import java.util.Base64
import java.io.File

def eprintln(message: String): Unit = {
  System.err.println(message)
}

object Main {

  // 設定値
  val VisionModelId = "gpt-4.1-nano-2025-04-14"
  val EmbeddingModelId = "text-embedding-3-small"
  val VisionPrompt =
    "この画像を説明する文章を生成してください。この文章をもとにembedding vectorを生成し、検索に利用する前提で文章を生成してください。"
  val VisionMaxTokens = 1024
  val ApiTimeout = 60.seconds
  val DictFileName = "dict.cbor"

  def main(args: Array[String]): Unit = {
    eprintln("Zero-shot Image Embedder")
    eprintln("画像を処理し、OpenAI APIでラベル付け、ベクトル化します")

    if (args.isEmpty) {
      eprintln("使用方法: [プログラム] <画像ファイルパス>")
      sys.exit(1)
    }

    val imagePathStr = args.head
    val imageFile = new File(imagePathStr)

    if (!imageFile.exists() || !imageFile.isFile) {
      eprintln(s"指定されたファイル '$imagePathStr' が見つかりません")
      sys.exit(1)
    }

    eprintln(s"画像ファイル: $imagePathStr")

    val apiToken = sys.env.get("OPENAI_TOKEN").getOrElse {
      eprintln("環境変数 OPENAI_TOKEN が設定されていません")
      sys.exit(1)
    }
    eprintln("環境変数 OPENAI_TOKEN が設定されています")

    implicit val system = ActorSystem("OpenAIClientSystem")

    try {
      val openAIService = OpenAIServiceFactory(apiToken)

      val fileVec =
        try {
          eprintln("画像を処理しています...")
          val image = ImmutableImage.loader().fromFile(imageFile)
          eprintln(s"画像サイズ: ${image.width}x${image.height}")

          // リサイズ (OpenAI推奨サイズ)
          val maxWidth = 2000
          val maxHeight = 768
          val resizedImage = image.bound(maxWidth, maxHeight)
          eprintln(s"リサイズ後: ${resizedImage.width}x${resizedImage.height}")

          // JPEGに変換してBase64エンコード
          val jpegWriter = JpegWriter().withCompression(85)
          val bytes = resizedImage.bytes(jpegWriter)
          val base64Image = Base64.getEncoder.encodeToString(bytes)
          eprintln(s"Base64エンコード完了 (${base64Image.length} 文字)")

          val visionFuture = openAIService.createModelResponse(
            Inputs.Items(
              Input.ofInputMessage(
                Seq(
                  InputMessageContent.Text(
                    VisionPrompt
                  ),
                  InputMessageContent.Image(imageUrl =
                    Some("data:image/jpeg;base64," + base64Image)
                  )
                ),
                role = ChatRole.User
              )
            ),
            CreateModelResponseSettings(
              model = VisionModelId,
              maxOutputTokens = Some(VisionMaxTokens)
            )
          )

          val visionResponse = Await.result(visionFuture, ApiTimeout)

          eprintln("Response API からレスポンスを受信しました")

          val imageLabel = visionResponse.outputText.headOption
            .getOrElse(
              throw new RuntimeException("Vision API から有効なレスポンスを取得できませんでした")
            )

          eprintln(s"画像ラベル: ${imageLabel}")

          eprintln("Embedding API を呼び出しています...")

          val embeddingSettings = CreateEmbeddingsSettings(
            model = EmbeddingModelId,
            encoding_format = Some(EmbeddingsEncodingFormat.float)
          )

          val embeddingFuture =
            openAIService.createEmbeddings(Seq(imageLabel), embeddingSettings)
          val embeddingResponse = Await.result(embeddingFuture, ApiTimeout)

          eprintln("Embedding API からレスポンスを受信しました")

          val embeddingVector = embeddingResponse.data.headOption
            .map(_.embedding)
            .getOrElse(
              throw new RuntimeException("Embedding API から有効なベクトルを取得できませんでした")
            )

          eprintln(s"埋め込みベクトル次元数: ${embeddingVector.length}")

          embeddingVector
        } catch {
          case e: Exception =>
            eprintln(s"APIの呼び出し中にエラーが発生しました: ${e.getMessage}")
            e.printStackTrace()
            sys.exit(1)
        } finally {
          openAIService.close()
        }

      // ベクトルをCBORとしてファイルに保存する
      // ファイル名->ベクトルのMapとして保存する

      // ファイルが既に存在する場合は読み込んでマージする
      import io.bullet.borer.Cbor

      val fileExists = os.exists(os.pwd / DictFileName)
      val initialMap = if (fileExists) {
        eprintln(s"ファイル '$DictFileName' が既に存在します。マージします。")
        // 既存のファイルを読み込む
        val existingData = os.read.bytes(os.pwd / DictFileName)
        Cbor.decode(existingData).to[Map[String, Seq[Double]]].value
      } else {
        eprintln(s"ファイル '$DictFileName' は存在しません。新規作成します。")
        Map.empty[String, Seq[Double]]
      }
      val updatedMap = initialMap + (imagePathStr -> fileVec.toSeq)
      val outputCbor = Cbor.encode(updatedMap)
      os.write.over(os.pwd / DictFileName, outputCbor.toByteArray)
    } finally {
      // ActorSystemの終了
      system.terminate()
    }

  }
}
