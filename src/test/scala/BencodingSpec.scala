package torentator

import org.scalatest.FlatSpec
import org.scalatest.prop.TableDrivenPropertyChecks._

class BencodingSpec extends FlatSpec {
    import Bencoding._
    import util.Success

    "Bencoding parser" should "parse strings" in {
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

    it should "calculate hash for value" in {
        val infoValue = "foobar"
        val infoValueHash =  java.security.MessageDigest.getInstance("SHA1").digest(infoValue.getBytes)

        assert (infoHash(s"d8:announce4:spam4:info${infoValue}e".getBytes) === infoValueHash)
    }

    it should "parse byte array from string" in {
        val expected = List(0x16, 0x19, 0xec, 0xc9, 0x37,
            0x3c, 0x36, 0x39, 0xf4, 0xee,
            0x3e, 0x26, 0x16, 0x38, 0xf2,
            0x9b, 0x33, 0xa6, 0xcb, 0xd6).map(_.toByte)

        val actual = parseByteArray("1619ecc9373c3639f4ee3e261638f29b33a6cbd6")

        assert(actual === expected)
    }

    // From https://wiki.theory.org/BitTorrentSpecification
    // For a 20-byte hash of \x12\x34\x56\x78\x9a\xbc\xde\xf1\x23\x45\x67\x89\xab\xcd\xef\x12\x34\x56\x78\x9a,
    // The right encoded form is %124Vx%9A%BC%DE%F1%23Eg%89%AB%CD%EF%124Vx%9A
    it should "url encode wiki.theory.org/BitTorrentSpecification sample" in {
        val in = parseByteArray("123456789abcdef123456789abcdef123456789a")
        val encoded = urlEncode(in)

        assert("%124Vx%9A%BC%DE%F1%23Eg%89%AB%CD%EF%124Vx%9A" === encoded)
    }

    //From http://torrent.ubuntu.com:6969/ 
    def urlEncoded = Table (
        ("info hash column", "info hash encoded in torrent link"),
        ("1619ecc9373c3639f4ee3e261638f29b33a6cbd6", "%16%19%EC%C97%3C69%F4%EE%3E%26%168%F2%9B3%A6%CB%D6"),
        ("d6c2b6947ea61a552b274126ad83d8956c21f205", "%D6%C2%B6%94%7E%A6%1AU%2B%27A%26%AD%83%D8%95l%21%F2%05"),
        ("d0db7206aaf48fafaf676bfddbc8922339abbde8", "%D0%DBr%06%AA%F4%8F%AF%AFgk%FD%DB%C8%92%239%AB%BD%E8"),
        ("efacbcb0b8d3dccf3adc2112588789e3aa986f04", "%EF%AC%BC%B0%B8%D3%DC%CF%3A%DC%21%12X%87%89%E3%AA%98o%04"),
        ("79a48a2bbd41e44b50cffbf5fdac62ca8358ee29", "y%A4%8A%2B%BDA%E4KP%CF%FB%F5%FD%ACb%CA%83X%EE%29"),
        ("d6c2b6947ea61a552b274126ad83d8956c21f205", "%D6%C2%B6%94%7E%A6%1AU%2B%27A%26%AD%83%D8%95l%21%F2%05"),
        ("da517e768e7884f7411c670fb9ada48f8fa0c403", "%DAQ%7Ev%8Ex%84%F7A%1Cg%0F%B9%AD%A4%8F%8F%A0%C4%03"),
        ("4e9898f848dbd5fce5713522885b301683d962f2", "N%98%98%F8H%DB%D5%FC%E5q5%22%88%5B0%16%83%D9b%F2"),
        ("7075254ca216123a3ea05a6c38307290ad2264ca", "pu%25L%A2%16%12%3A%3E%A0Zl80r%90%AD%22d%CA")
    )

    forAll (urlEncoded) { (byteArrayAsString: String, urlEncoded: String) =>
        val byteArray = parseByteArray(byteArrayAsString)
        assert (urlEncode(byteArray) === urlEncoded)
    }    
}
