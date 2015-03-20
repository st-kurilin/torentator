package torentator.bencoding

import org.scalatest.FlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks._

class BencodingSpec extends FlatSpec {
  import Bencode._
  import util.Success

  "Bencode parser" should "parse strings" in {
    assert(parse("4:spam") === Success(BString("spam")))
  }

  it should "parse parse strings with spaces" in {
    assert(parse("4:sp m") === Success(BString("sp m")))
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

  it should "parse lists in dicts" in {
    assert(parse("d4:spaml1:a1:bee") === Success(BDictionary(
      Map("spam" -> BList(List(BString("a"), BString("b")))))))
  }

  it should "parse dicts in lists" in {
    assert(parse("ld4:spami23e2:spi3eed3:abc4:defgee") === Success(BList(List(
      BDictionary(Map("spam" -> BInteger(23), "sp"  -> BInteger(3))),
      BDictionary(Map("abc" -> BString("defg")))))))
  }
}
