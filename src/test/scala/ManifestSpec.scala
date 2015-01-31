package torentator

import org.scalatest.FlatSpec

class ManifestSpec extends FlatSpec {
    "Manifest" should "return 1 for test method" in {
        val m = new Manifest()
        assert(m.test === 1)
    }
}
