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

    it should "parse list of inters" in {
        assert(parse("li3ei21ee") === Success(BList(List(BInteger(3), BInteger(21)))))
    }

    it should "parse list of strings" in {
     assert(parse("l4:spam4:eggse") === Success(BList(List(BString("spam"), BString("eggs")))))   
    }

    it should "parse dicts" in {
         assert(parse("d3:cow3:moo4:spam4:eggse") === Success(BDictionary(
             Map("cow" -> BString("moo"), "spam" -> BString("eggs")))))
    }

    it should "parse dicts with lists" in {
        assert(parse("d4:spaml1:a1:bee") === Success(BDictionary(
            Map("spam" -> BList(List(BString("a"), BString("b")))))))
    }
}