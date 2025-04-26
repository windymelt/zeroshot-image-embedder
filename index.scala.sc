//> using scala 3.6
//> using dep "com.lihaoyi::os-lib:0.9.3"
//> using dep "io.bullet::borer-core:1.10.3"
//> using dep com.softwaremill.sttp.client4::core:4.0.3
//> using dep io.circe::circe-generic:0.14.13
//> using dep io.circe::circe-core:0.14.13

import io.bullet.borer.Cbor
import sttp.client4.quick.*
import io.circe.syntax.*
import io.circe.*
import io.circe.Json.*

val DictFileName = "dict.cbor"
val MeilisearchUrl = "http://localhost:7700"

// load dict
val dictFile = os.pwd / DictFileName
val dict = if (os.exists(dictFile)) {
  val dict = Cbor.decode(os.read.bytes(dictFile)).to[Map[String, Array[Float]]]
  println(s"Loaded dictionary.")
  dict
} else {
  println(s"Dictionary file '$DictFileName' does not exist.")
  sys.exit(1)
}

val dictMap =
  Cbor.decode(os.read.bytes(dictFile)).to[Map[String, Seq[Double]]].value

println(s"Loaded dictionary with ${dictMap.size} entries.")

def calcSha256(file: os.Path): String = {
  val bytes = os.read.bytes(file)
  val sha256 = java.security.MessageDigest.getInstance("SHA-256")
  sha256.update(bytes)
  sha256.digest().map("%02x".format(_)).mkString
}

for {
  (fileName, vector) <- dictMap
} {
  val filePath = os.pwd / fileName
  if (os.exists(filePath)) {
    val sha256 = calcSha256(filePath)
    println(s"File: $fileName, SHA-256: $sha256")
    val bodyJson = JsonObject.fromMap(
      Map(
        "sha256" -> Json.fromString(sha256),
        "_vectors" -> JsonObject
          .fromMap(
            Map(
              "photoEmbedder" -> Json.fromValues(
                vector.map(Json.fromDouble(_).get)
              )
            )
          )
          .toJson,
        "filePath" -> Json.fromString(filePath.toString)
      )
    )
    val body = bodyJson.asJson.noSpaces
    val resp = quickRequest
      .post(
        uri"$MeilisearchUrl/indexes/photo_index/documents?primaryKey=sha256"
      )
      .body(body)
      .header("Content-Type", "application/json")
      .send()
    println(resp.body)
  } else {
    println(s"File not found: $fileName")
  }
}
