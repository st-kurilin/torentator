package torentator

object Bencoding {
  sealed trait Bencode
  case class BString(value: String) extends Bencode
  case class BInteger(value: Long) extends Bencode
  case class BList(value: Seq[Bencode]) extends Bencode
  case class BDictionary(value: Map[String, Bencode]) extends Bencode

  object BString {
    def apply(s: Seq[Byte]): BString = {
      BString(new String(s.toArray, "ISO-8859-1"))
    }
  }

  object TextString {
    def unapply(x: Bencode): Option[String] = x match {
      case BString(str) => Some(str)
      case _ => None
    }
  }

  object BinaryString {
    def unapply(x: Bencode): Option[Seq[Byte]] = x match {
      case BString(str) => Some(str.getBytes("ISO-8859-1"))
      case _ => None
    }
  }

  import scala.util.parsing.combinator._

  object Parser extends JavaTokenParsers {
    def anyChar: Parser[Char] = new Parser[Char] {
      override def apply(in: Input): ParseResult[Char] = {
        Success(in.first, in.rest)
      }
    }
    def str: Parser[BString] = (wholeNumber ^^ {_.toInt}) >> { n=> ":" ~> repN(n, anyChar)} ^^ {
      case x => BString(x.mkString(""))
    }
    def int: Parser[BInteger] = ("i" ~>  wholeNumber <~ "e") ^^ {case x => BInteger(x.toInt)}
    def list: Parser[BList] = ("l" ~>  rep(expr) <~ "e") ^^ {case x => BList(x)}
    def dict: Parser[BDictionary] = ("d" ~> rep(repN(2, expr)) <~ "e") ^^ {case x =>
      BDictionary(x.foldLeft(Map.empty[String, Bencode]) {
        case (r, BString(key)::(value:Bencode)::Nil) =>
          r + (key -> value)
        case f => throw new RuntimeException("Bencoding: could not parse dictionary. Failed on: " + f)
      })
    }
    def expr: Parser[Bencode] = str | int | list | dict

    def apply(str: String): util.Try[Bencode] = {
      parseAll(expr, str) match {
        case Success(r, _) => util.Success(r)
        case Failure(r, o) => util.Failure(new RuntimeException(
          s"Failure: [r] on pos ${o.pos} during processing char ${o.first.toInt}"))
        case Error(r, o) => util.Failure(new RuntimeException(
          s"Error: [r] on pos ${o.pos} during processing char ${o.first.toInt}"))
      }
    }
  }

  def parse(str: String): util.Try[Bencode] = {
    Parser.apply(str)
  }

  def urlEncode(hash: Seq[Byte]): String = {
    val ints = hash.map(_.toInt & 0xFF)
    val asIs = Set('-', '.', '_') map (_.toInt)
    val asIsRanges = Set(
      '0'.toInt to '9'.toInt,
      'a'.toInt to 'z'.toInt,
      'A'.toInt to 'Z'.toInt)

    val out = new StringBuilder();
    for (letter <- ints) {
      if (asIs.contains(letter) || asIsRanges.exists(_.contains(letter))) {
        out.append(letter.toChar);
      } else {
        var s = Integer.toHexString(letter).toUpperCase()
        if (s.length() == 1) {
          s = "0" + s;
        }
        out.append("%" + s.substring(s.length() - 2, s.length()))
      }
    }
    out.toString();
  }

  def infoHash(str: Seq[Byte]): Seq[Byte] = {
    def subindex(big: Seq[Byte], bi: Int, small: Seq[Byte], si: Int): Option[Int] = {
      require(bi >= 0 && si >= 0)
      val (sl, bl) = (small.size, big.size)
      (bi, si) match {
        case (_, `sl`) => Some(bi - si)
        case (`bl`, _) => None
        case _ if big(bi) == small(si) => subindex(big, bi + 1, small, si + 1)
        case _ => subindex(big, bi - si + 1, small, 0)
      }
    }
    val infoArray = "info".getBytes.toList
    val ss = subindex(str, 0, infoArray, 0)
    val startIndex = ss.get + infoArray.size
    val endIndex = str.length - 1

    val infoValue = str.slice(startIndex, endIndex)
    val seq = str.slice(startIndex, endIndex)
    hash(seq)
  }

  def hash(data: Seq[Byte]): Seq[Byte] = java.security.MessageDigest.getInstance("SHA1").digest(data.toArray)

  def parseByteArray(s: String): Seq[Byte] = {
    def parseHex(x: String): Byte = Integer.parseInt(x, 16).toByte
    val o: Tuple2[String, List[Byte]] = (("", List.empty[Byte]))
    val (_, r) = s.foldRight(o) { (x, r) =>
      val (cur, res) = r
      if (cur.isEmpty) (x.toString, res)
      else ("", parseHex(x + cur) :: res)
    }
    r
  }

  def getBytes(str: String): Seq[Byte] = {
    str.getBytes("ISO-8859-1")
  }
}


