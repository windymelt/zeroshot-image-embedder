// 基本的なScala機能のみ使用
import scala.util.{Try, Success, Failure}
import scala.io.StdIn

// 画像処理
import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import java.util.Base64
import java.io.File

// JSON処理
import io.circe.syntax._
import io.circe.Printer
import io.circe.generic.auto._

object Main {

  def main(args: Array[String]): Unit = {
    println("Zero-shot Image Embedder")
    println("画像を取得し、処理します（今回はOpenAI APIは呼び出しません）")

    // コマンドライン引数のチェック
    if (args.isEmpty) {
      System.err.println("使用方法: [プログラム] <画像ファイルパス>")
      System.exit(1)
    }

    val imagePathStr = args.head
    val imageFile = new File(imagePathStr)

    // ファイル存在チェック
    if (!imageFile.exists() || !imageFile.isFile) {
      System.err.println(s"指定されたファイル '$imagePathStr' が見つかりません")
      System.exit(1)
    }

    println(s"画像ファイル: $imagePathStr")

    // APIトークンチェック - 実際には使用しないが、確認のため
    val apiTokenPresent = sys.env.get("OPENAI_TOKEN").isDefined
    println(
      s"環境変数 OPENAI_TOKEN 設定状況: ${if (apiTokenPresent) "設定済み" else "未設定"}"
    )

    try {
      println("画像を読み込んでいます...")
      val image = ImmutableImage.loader().fromFile(imageFile)
      println(s"画像サイズ: ${image.width}x${image.height}")

      // リサイズ (OpenAI Vision APIの推奨サイズ)
      val maxWidth = 2000
      val maxHeight = 768
      val resizedImage = image.bound(maxWidth, maxHeight)
      println(s"リサイズ後: ${resizedImage.width}x${resizedImage.height}")

      // JPEGに変換
      val jpegWriter = JpegWriter().withCompression(85)
      val bytes = resizedImage.bytes(jpegWriter) // 明示的にwriterを渡す

      // Base64エンコード
      val base64Image = Base64.getEncoder.encodeToString(bytes)
      println(s"Base64エンコード完了 (${base64Image.length} 文字)")

      // 成功
      println("画像の処理が完了しました。")

      // 出力例: ダミーのベクトルデータを出力
      val dummyVector = Seq(0.1, 0.2, 0.3, 0.4, 0.5)
      val jsonOutput = dummyVector.asJson.printWith(Printer.noSpaces)
      println("\nダミーベクトル出力例:")
      println(jsonOutput)

    } catch {
      case e: Exception =>
        System.err.println(s"エラーが発生しました: ${e.getMessage}")
        e.printStackTrace()
        System.exit(1)
    }
  }
}
