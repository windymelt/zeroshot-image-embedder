import scala.util.{Try, Success, Failure}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

import os._ // For file path operations and environment variables

// OpenAI Client imports
import io.cequence.openaiscala.domain._
import io.cequence.openaiscala.domain.settings._
import io.cequence.openaiscala.service.{
  OpenAIService,
  OpenAIServiceFactory
} // Use Factory for resource management
import io.cequence.openaiscala.service.CloseableService

import com.sksamuel.scrimage.ImmutableImage // Image processing
import com.sksamuel.scrimage.nio.JpegWriter // Writing image as JPEG
import com.sksamuel.scrimage.ScaleMethod // Scaling methods
import java.util.Base64 // Base64 encoding
import java.nio.file.Paths // Path handling for scrimage

// Circe imports for JSON handling
import io.circe.syntax._
import io.circe.Printer
import io.circe.generic.auto._ // Automatic derivation for simple types like Seq[Double]

object Main {

  // --- Configuration ---
  val VisionModelId = ModelId.gpt_4_vision_preview
  val EmbeddingModelId = ModelId.text_embedding_ada_002
  val VisionMaxTokens = 1024 // Max tokens for the vision response (label)
  val ImageResizeMaxWidth = 2000 // OpenAI recommended max long side
  val ImageResizeMaxHeight = 768 // OpenAI recommended max short side
  val VisionPrompt = "この画像をラベリングしてください。"
  val ApiTimeout = 60.seconds // Timeout for API calls

  // --- Main Application Logic ---
  def main(args: Array[String]): Unit = {

    val clientTry: Try[CloseableService[OpenAIService]] = Try {
      val apiToken = os.env.get("OPENAI_TOKEN").getOrElse {
        throw new IllegalArgumentException("環境変数 OPENAI_TOKEN が設定されていません。")
      }
      OpenAIServiceFactory.apply(apiToken)
    }

    // The result now yields the embedding vector (Seq[Double]) on success
    val result: Try[Seq[Double]] = clientTry.flatMap { client =>
      client.use { service =>
        for {
          // 1. Get image path
          imagePathStr <- Try(
            args.headOption.getOrElse(
              throw new IllegalArgumentException("画像ファイルのパスを引数に指定してください。")
            )
          )
          imagePath = os.Path(imagePathStr, base = os.pwd)

          // 2. Check file existence
          _ <- Try(
            if (!os.isFile(imagePath))
              throw new java.io.FileNotFoundException(
                s"指定されたファイルが見つかりません: $imagePath"
              )
            else ()
          )

          // 3. Image Processing
          base64Image <- Try {
            println(s"画像を処理中: $imagePath") // Keep user informed
            val image =
              ImmutableImage.loader().fromPath(Paths.get(imagePath.toString))
            val resizedImage =
              image.bound(ImageResizeMaxWidth, ImageResizeMaxHeight)
            // println(s"画像サイズ変更後: ${resizedImage.width}x${resizedImage.height}") // Optional debug info
            implicit val writer: JpegWriter = JpegWriter().withCompression(85)
            val jpegBytes: Array[Byte] = resizedImage.bytes
            val encodedString = Base64.getEncoder.encodeToString(jpegBytes)
            // println(s"Base64エンコード完了 (最初の30文字): ${encodedString.take(30)}...") // Optional debug info
            encodedString
          }.recoverWith { case e: Exception =>
            Failure(
              new RuntimeException(s"画像処理中にエラーが発生しました: ${e.getMessage}", e)
            )
          }

          // 4. Call Vision API
          imageLabel <- Try {
            println("Vision API を呼び出し中...") // Keep user informed
            val messages = Seq(
              UserMessage(content =
                Seq(
                  TextContent(VisionPrompt),
                  ImageURLContent(s"data:image/jpeg;base64,$base64Image")
                )
              )
            )
            val request = ChatCompletionRequest(
              model = VisionModelId,
              messages = messages,
              max_tokens = Some(VisionMaxTokens),
              temperature = Some(0.0)
            )
            val responseFuture = service.createChatCompletion(request)
            val response = Await.result(responseFuture, ApiTimeout)
            val label = response.choices.headOption
              .flatMap(_.message.content)
              .getOrElse(
                throw new RuntimeException("Vision API から有効なラベルを取得できませんでした。")
              )
            // println(s"Vision API からラベル取得: ${label.take(50)}...") // Optional debug info
            label
          }.recoverWith { case e: Exception =>
            Failure(
              new RuntimeException(
                s"Vision API 呼び出し中にエラーが発生しました: ${e.getMessage}",
                e
              )
            )
          }

          // 5. Call Embedding API
          embeddingVector <- Try {
            println("Embedding API を呼び出し中...") // Keep user informed
            val request = EmbeddingRequest(
              model = EmbeddingModelId,
              input =
                Seq(imageLabel) // Input is the label obtained from Vision API
            )
            val responseFuture: Future[EmbeddingResponse] =
              service.createEmbeddings(request)
            val response = Await.result(responseFuture, ApiTimeout)

            // Extract the first embedding vector
            val vector = response.data.headOption
              .map(_.embedding)
              .getOrElse(
                throw new RuntimeException(
                  "Embedding API から有効なベクトルを取得できませんでした。"
                )
              )
            // println(s"Embedding API からベクトル取得完了 (次元数: ${vector.length})") // Optional debug info
            vector
          }.recoverWith { case e: Exception =>
            Failure(
              new RuntimeException(
                s"Embedding API 呼び出し中にエラーが発生しました: ${e.getMessage}",
                e
              )
            )
          }

        } yield embeddingVector // Yield the final embedding vector
      }
    }

    // --- Handle results ---
    result match {
      case Success(vector) =>
        // 6. Output JSON
        Try {
          // Use Circe to convert Seq[Double] to JSON string (compact format)
          val jsonOutput = vector.asJson.printWith(Printer.noSpaces)
          println(jsonOutput) // Print the final JSON to standard output
          System.exit(0) // Exit successfully
        }.recover { case e: Exception =>
          System.err.println(
            s"[エラー] JSONへの変換または出力中にエラーが発生しました: ${e.getMessage}"
          )
          System.exit(1)
        }

      case Failure(exception) =>
        System.err.println(s"[エラー] ${exception.getMessage}")
        // exception.printStackTrace() // Uncomment for detailed stack trace
        System.exit(1)
    }
  }
}
