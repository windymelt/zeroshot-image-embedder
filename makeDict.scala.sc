//> using scala 3.6
//> using dep "com.lihaoyi::os-lib:0.9.3"
//> using dep "io.bullet::borer-core:1.10.3"
//> using dep "io.cequence::openai-scala-client:1.2.0"
//> using dep "com.sksamuel.scrimage:scrimage-core:4.3.1"
//> using dep "com.sksamuel.scrimage:scrimage-formats-extra:4.3.1"

import scala.util.{Try, Success, Failure}
import scala.concurrent.{Await, Future, ExecutionContext, blocking}
import scala.concurrent.duration._
import akka.actor.Terminated

import akka.actor.ActorSystem

import io.cequence.openaiscala.domain._
import io.cequence.openaiscala.service.{OpenAIService, OpenAIServiceFactory}
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
import os.Path // os-lib を使うため
import io.bullet.borer.Cbor // CBOR 関連

def eprintln(message: String): Unit = {
  System.err.println(message)
}

// 設定値
val VisionModelId = "gpt-4.1-nano-2025-04-14"
val EmbeddingModelId = "text-embedding-3-small"
val VisionPrompt =
  """この画像を説明する文章を生成してください。
    |この文章をもとにembedding vectorを生成し、検索に利用する前提で文章を生成してください。
    |あなたは、画像を説明する文章のみを出力してください。このプロンプトへの返答(例: 「わかりました」)を出力するのを禁止します。
    |「この画像は」といった前口上は不要です。
    |出力例: 城の周りに木々が生いしげっている。敷地では桜が咲いており、人々が周りに集まっている。天守閣の周りには石垣がそびえ立っている。"""
val VisionMaxTokens = 1024
val ApiTimeout = 60.seconds
val DictFileName = "dict.cbor"

// --- リソース管理 ---
private def setupResources(
    apiToken: String
)(implicit ec: ExecutionContext): (ActorSystem, OpenAIService) = {
  eprintln("リソースを初期化しています...")
  implicit val system: ActorSystem = ActorSystem("OpenAIClientSystem")
  val service = OpenAIServiceFactory(apiToken)
  eprintln("リソースの初期化完了。")
  (system, service)
}

private def cleanupResources(system: ActorSystem, service: OpenAIService)(
    implicit ec: ExecutionContext
): Future[Terminated] = {
  eprintln("リソースを解放しています...")
  // openaiscala v0.4.x 以降、close() は同期的な操作かもしれないため Future でラップしない
  Try(service.close()) // 同期的かもしれないのでTryで囲む
  system.terminate().andThen { case _ => eprintln("リソースの解放完了。") }
}

// --- 引数解析 ---
private def parseArguments(args: Array[String]): Try[Seq[File]] = Try {
  if (args.isEmpty) {
    throw new IllegalArgumentException(
      "使用方法: [プログラム] <画像ファイルパス> [<画像ファイルパス> ...]"
    )
  }
  val files = args.map(new File(_))
  val (existFiles, nonExistFiles) =
    files.partition(f => f.exists() && f.isFile)

  if (nonExistFiles.nonEmpty) {
    nonExistFiles.foreach(f =>
      eprintln(s"エラー: ファイルが見つからないか、ファイルではありません: ${f.getPath}")
    )
    throw new IllegalArgumentException("存在しないファイルが指定されました。")
  }
  if (existFiles.isEmpty) {
    throw new IllegalArgumentException("処理対象の有効なファイルがありません。")
  }
  eprintln(s"${existFiles.length} 個の画像ファイルを認識しました。")
  existFiles.toSeq // Seq[File] を返す
}

// --- 画像処理 ---
private def processImageFile(imageFile: File): Try[String] = Try {
  eprintln(s"画像ファイルを処理中: ${imageFile.getPath}")
  val image = ImmutableImage.loader().fromFile(imageFile)
  eprintln(s"  元のサイズ: ${image.width}x${image.height}")

  // リサイズ (OpenAI推奨サイズ)
  val maxWidth = 2000
  val maxHeight = 768
  val resizedImage = image.bound(maxWidth, maxHeight)
  eprintln(s"  リサイズ後: ${resizedImage.width}x${resizedImage.height}")

  // JPEGに変換してBase64エンコード
  // blocking { ... } で囲むことで、潜在的なブロッキング I/O 操作であることを示す
  blocking {
    val jpegWriter = JpegWriter().withCompression(85)
    val bytes = resizedImage.bytes(jpegWriter)
    val base64Image = Base64.getEncoder.encodeToString(bytes)
    eprintln(s"  Base64エンコード完了 (${base64Image.length} 文字)")
    base64Image
  }
}

// --- OpenAI API 連携 ---
private def fetchImageLabel(
    base64Image: String
)(implicit service: OpenAIService, ec: ExecutionContext): Future[String] = {
  eprintln("Vision API を呼び出しています...")
  val visionFuture = service.createModelResponse(
    Inputs.Items(
      Input.ofInputMessage(
        Seq(
          InputMessageContent.Text(VisionPrompt),
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

  visionFuture
    .map { response =>
      eprintln("Vision API からレスポンスを受信しました。")
      response.outputText.headOption.getOrElse {
        // Future.failed でエラーを表現
        throw new RuntimeException("Vision API から有効なレスポンスを取得できませんでした")
      }
    }
    .recoverWith { case e: Exception =>
      // API呼び出し自体の失敗も Future.failed でラップ
      Future.failed(
        new RuntimeException(s"Vision API 呼び出し中にエラー: ${e.getMessage}", e)
      )
    }
}

private def fetchEmbeddingVector(label: String)(implicit
    service: OpenAIService,
    ec: ExecutionContext
): Future[Seq[Double]] = {
  eprintln("Embedding API を呼び出しています...")
  val embeddingSettings = CreateEmbeddingsSettings(
    model = EmbeddingModelId,
    encoding_format = Some(EmbeddingsEncodingFormat.float)
  )

  val embeddingFuture =
    service.createEmbeddings(Seq(label), embeddingSettings)

  embeddingFuture
    .map { response =>
      eprintln("Embedding API からレスポンスを受信しました。")
      response.data.headOption
        .map(_.embedding)
        .getOrElse {
          // Future.failed でエラーを表現
          throw new RuntimeException("Embedding API から有効なベクトルを取得できませんでした")
        }
    }
    .recoverWith { case e: Exception =>
      // API呼び出し自体の失敗も Future.failed でラップ
      Future.failed(
        new RuntimeException(s"Embedding API 呼び出し中にエラー: ${e.getMessage}", e)
      )
    }
}

// --- CBOR 辞書操作 ---
private def loadExistingDictionary(
    dictPath: Path
): Try[Map[String, Seq[Double]]] = Try {
  if (os.exists(dictPath)) {
    eprintln(s"既存の辞書ファイル '$dictPath' を読み込みます...")
    // blocking { ... } でファイルI/Oを囲む
    blocking {
      val existingData = os.read.bytes(dictPath)
      Cbor.decode(existingData).to[Map[String, Seq[Double]]].value
    }
  } else {
    eprintln(s"辞書ファイル '$dictPath' は存在しません。空の辞書を使用します。")
    Map.empty[String, Seq[Double]]
  }
}

private def saveDictionary(
    dictPath: Path,
    dictionary: Map[String, Seq[Double]]
): Try[Unit] = Try {
  eprintln(s"辞書をファイル '$dictPath' に保存します...")
  // blocking { ... } でファイルI/Oを囲む
  blocking {
    val outputCbor = Cbor.encode(dictionary).toByteArray
    os.write.over(dictPath, outputCbor, createFolders = true) // 必要なら親フォルダも作成
    eprintln("辞書の保存が完了しました。")
  }
}

// ExecutionContext を明示的に生成・利用する
implicit val ec: ExecutionContext = ExecutionContext.global // 必要に応じてカスタマイズ

eprintln("Zero-shot Image Embedder")
eprintln("画像を処理し、OpenAI APIでラベル付け、ベクトル化します")

// --- 初期化処理 ---
val program = for {
  imageFiles <- parseArguments(args)
  apiToken <- Try(sys.env("OPENAI_TOKEN")).recoverWith {
    case _: NoSuchElementException =>
      Failure(new RuntimeException("環境変数 OPENAI_TOKEN が設定されていません"))
  }
} yield (imageFiles, apiToken)

program match {
  case Success((imageFiles, apiToken)) =>
    eprintln("初期化成功。処理を開始します。")
    // リソース確保
    var systemOption: Option[ActorSystem] = None
    var serviceOption: Option[OpenAIService] = None
    try {
      val (system, service) = setupResources(apiToken)
      systemOption = Some(system)
      serviceOption = Some(service)
      implicit val s: ActorSystem = system
      implicit val serv: OpenAIService = service

      // --- CBOR辞書読み込み (後で実装) ---
      val dictPath = os.pwd / DictFileName
      // loadExistingDictionary は Try を返すので、ここで get せずに Try のまま扱うか、
      // main の for 内で処理するのが望ましいが、一旦元の構造に合わせて get する
      val initialDict = loadExistingDictionary(dictPath).getOrElse {
        eprintln(s"警告: 既存の辞書ファイル '$DictFileName' の読み込みに失敗しました。空の辞書から開始します。")
        Map.empty[String, Seq[Double]]
      }

      // --- ファイル逐次処理 ---
      eprintln(s"${imageFiles.length} 個のファイルを処理します...")

      // Future.foldLeft で逐次処理
      // アキュムレータは Future[Map[String, Seq[Double]]]
      val initialFuture = Future.successful(initialDict)

      val finalResultFuture: Future[Map[String, Seq[Double]]] =
        imageFiles.foldLeft(initialFuture) { (accFuture, currentFile) =>
          accFuture.flatMap { accumulatedMap =>
            eprintln(s"--- ファイル処理開始: ${currentFile.getPath} ---")
            // Future の for-comprehension で処理を連結
            val processFuture = for {
              base64Image <- Future.fromTry(processImageFile(currentFile))
              label <- fetchImageLabel(
                base64Image
              ) // implicit service, ec を使用
              vector <- fetchEmbeddingVector(
                label
              ) // implicit service, ec を使用
            } yield {
              eprintln(s"ラベル: $label")
              eprintln(s"埋め込みベクトル次元数: ${vector.length}")
              eprintln(s"--- ファイル処理完了: ${currentFile.getPath} ---")
              // 成功したら結果を Map に追加
              accumulatedMap + (currentFile.getPath -> vector)
            }

            // エラーが発生したら foldLeft を中断させる (Future.failed を伝播)
            processFuture.recoverWith { case e: Exception =>
              eprintln(
                s"!!! ファイル '${currentFile.getPath}' の処理中にエラーが発生しました: ${e.getMessage}"
              )
              Future.failed(e) // エラーを伝播させて foldLeft を停止
            }
          }
        }

      // Await で最終結果を待つ (エラー発生時はここで例外がスローされる)
      // タイムアウトはファイル数に応じて少し余裕を持たせる
      val totalTimeout = ApiTimeout * imageFiles.length + 30.seconds
      eprintln(s"すべてのファイル処理の完了を待ちます (タイムアウト: ${totalTimeout})...")
      val finalResultMap = Await.result(finalResultFuture, totalTimeout)

      // --- CBOR辞書書き込み (すべての処理が成功した場合のみ実行される) ---
      if (finalResultMap.size > initialDict.size) { // 何か新しい結果が追加されたか
        saveDictionary(dictPath, finalResultMap) match {
          case Success(_) => eprintln("最終的な辞書の保存に成功しました。")
          case Failure(e) =>
            // ここでエラーが発生した場合もプログラムは終了するが、ログは出す
            eprintln(s"!!! 最終的な辞書の保存中にエラーが発生しました: ${e.getMessage}")
            throw e // エラーを再スローしてプログラムを異常終了させる
        }
      } else {
        eprintln("新しい処理結果がないため、辞書ファイルは更新されませんでした。")
      }

      eprintln("すべての処理が正常に完了しました。")

    } catch {
      case e: Exception =>
        eprintln(s"処理中に予期せぬエラーが発生しました: ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1) // エラー終了
    } finally {
      // リソース解放
      (systemOption, serviceOption) match {
        case (Some(sys), Some(serv)) =>
          // cleanupResources に implicit ExecutionContext が必要
          Await.result(
            cleanupResources(sys, serv)(ec),
            ApiTimeout
          ) // 解放完了を待つ
        case _ => // 初期化前に失敗した場合など
      }
    }

  case Failure(e) =>
    eprintln(s"プログラムの初期化に失敗しました: ${e.getMessage}")
    sys.exit(1)
}
