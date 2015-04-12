package torentator.manifest

import org.scalatest._

class ManifestSpec extends FlatSpec with Matchers {
  import torentator.bencoding._
  import torentator.encoding.Encoder
  import util.{Success, Failure}
  import java.nio.file.Paths

  "Manifest" should "be creatable from single file becoding description" in {
  val name = "tpb"
  val announce = "http://tpb.tpb"
  val pieceLength = 36
  val lenght = 200
  val hash = List()
  val pieces = Seq.tabulate(4)(_ => Seq.tabulate(20)(n => n.toByte))

  val actual = Manifest.parse(BDictionary(Map(
    "announce" -> BString(announce),
    "info" -> BDictionary(Map(
    "name"    -> BString(name),
    "piece length"  -> BInteger(pieceLength),
    "pieces" -> BString(pieces.flatten),
    "length"  -> BInteger(lenght))))), hash)

  assert(actual === util.Success(
    new SingleFileManifest(name, new java.net.URI(announce), hash, pieces, pieceLength, lenght)))
  }

  it should "be creatable from multi file becoding description" in {
    val name = "tpb"
    val announce = "http://tpb.tpb"
    val piece = 36
    val files = List((134L, "foo/bar"), (256L, "foo"))
    val hash = List()

    val filesEncoded = BList(files map {
      case (l, f) => 
      val path = BList(f.split("/").map(BString(_)))
      BDictionary(Map("length" -> BInteger(l), "path" -> path))
    })

    val actual = Manifest.parse(BDictionary(Map(
      "announce" -> BString(announce),
      "info" -> BDictionary(Map(
      "name"    -> BString(name),
      "piece length"  -> BInteger(piece),
      "files"   -> filesEncoded)))), hash)

    assert(actual === util.Success(
    new MultiFileManifest(name, new java.net.URI(announce), hash, piece, files)))
  }

  it should "be creatable from real file (SingleFileManifest)" in {
    val manifest = Manifest.read(Paths.get("./src/test/resources/sample.single.http.torrent"))

    manifest match {
      case Success(SingleFileManifest(name, announce, hash, pieces, pieceLength, length)) => 
        name should not be empty
        announce should not be null
        hash should have size 20
        pieceLength should not be 0
        length should not be 0
        pieces should not be empty
        assert(hash === Encoder.parseByteArray("1619ecc9373c3639f4ee3e261638f29b33a6cbd6"))
      case Success(m) => fail(m.toString)
      case Failure(e) => fail(e)
    }
  }

  it should "be creatable from real file (MultiFileManifest)" in {
    val manifest = Manifest.read(Paths.get("./src/test/resources/sample.multi.udp.torrent"))

    manifest match {
      case Success(MultiFileManifest(name, announce, hash, pieceLength, files)) => 
        name should not be empty
        announce should not be null
        hash should have size 20
        pieceLength should not be 0
        files should not be empty
      case Success(m) => fail(m.toString)
      case Failure(e) => fail(e)
    }
  }
}
