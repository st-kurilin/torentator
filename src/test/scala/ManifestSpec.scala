package torentator

import org.scalatest.FlatSpec

class ManifestSpec extends FlatSpec {
    import Bencoding._

    "Manifest" should "be creatable from single file becoding description" in {
        val name = "tpb"
        val announce = "http://tpb.tpb"
        val piece = 36
        val lenght = 200
        val hash = List()

        val actual = Manifest(BDictionary(Map(
            "announce" -> BString(announce),
            "info" -> BDictionary(Map(
                "name"      -> BString(name), 
                "piece"     -> BInteger(piece),
                "length"    -> BInteger(lenght))))), hash)

        assert(actual === new SingleFileManifest(name, new java.net.URL(announce), hash, piece, lenght))
    }

    it should "be creatable from multi file becoding description" in {
        val name = "tpb"
        val announce = "http://tpb.tpb"
        val piece = 36
        val paths = List("foo/bar", "foo")
        val hash = List()

        val actual = Manifest(BDictionary(Map(
            "announce" -> BString(announce),
            "info" -> BDictionary(Map(
                "name"      -> BString(name), 
                "piece" -> BInteger(piece),
                "path" -> BList(paths.map(BString(_))))))), hash)

        assert(actual === new MultiFileManifest(name, new java.net.URL(announce), hash, piece, paths))
    }
}
