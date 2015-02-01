package torentator

import org.scalatest.FlatSpec

class BencodingSpec extends FlatSpec {
    import Bencoding._
    import util.Success

    "Bencoding parser" should "parse strings" in {
        assert(parse("4:spam") === Success(BString("spam")))
    }

    it should "parse integers " in {
        assert(parse("i3e") === Success(BInteger(3)))
    }
}
