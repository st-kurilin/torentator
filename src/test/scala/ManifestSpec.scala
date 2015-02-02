package torentator

import org.scalatest.FlatSpec

class ManifestSpec extends FlatSpec {
    import Bencoding._

    "Manifest" should "be creatable from single file becoding description" in {
        val name = "tpb"
        val announce = "http://tpb.tpb"
        val piece = 36
        val lenght = 200

        val actual = Manifest(BDictionary(Map(
            "announce" -> BString(announce),
            "info" -> BDictionary(Map(
                "name"      -> BString(name), 
                "piece"     -> BInteger(piece),
                "length"    -> BInteger(lenght))))))

        assert(actual === new SingleFileManifest(name, new java.net.URL(announce), piece, lenght))
    }

    it should "be creatable from multi file becoding description" in {
        val name = "tpb"
        val announce = "http://tpb.tpb"
        val piece = 36
        val paths = List("foo/bar", "foo")

        val actual = Manifest(BDictionary(Map(
            "announce" -> BString(announce),
            "info" -> BDictionary(Map(
                "name"      -> BString(name), 
                "piece" -> BInteger(piece),
                "path" -> BList(paths.map(BString(_))))))))

        assert(actual === new MultiFileManifest(name, new java.net.URL(announce), piece, paths))
    }
}
