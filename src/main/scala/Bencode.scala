package torentator.bencoding

import torentator.encoding.Encoder._

/** Contains Bencode format parser */

sealed trait Bencode
case class BString(value: String) extends Bencode
case class BInteger(value: Long) extends Bencode
case class BList(value: Seq[Bencode]) extends Bencode
case class BDictionary(value: Map[String, Bencode]) extends Bencode

object BString {
  def apply(b: Seq[Byte]): BString = BString(bytesToStr(b))
}

object TextString {
  def unapply(x: Bencode): Option[String] = x match {
    case BString(str) => Some(str)
    case _ => None
  }
}

object BinaryString {
  def unapply(x: Bencode): Option[Seq[Byte]] = x match {
    case BString(str) => Some(strToBytes(str))
    case _ => None
  }
}

object Bencode {
  def parse(str: String): util.Try[Bencode] = {
    Parser.apply(str)
  }

  import scala.util.parsing.combinator._
  private object Parser extends JavaTokenParsers {
    def anyChar: Parser[Char] = new Parser[Char] {
      override def apply(in: Input): ParseResult[Char] = {
        Success(in.first, in.rest)
      }
    }
    def dictContent(x: List[List[Bencode]]) = 
      BDictionary(x.foldLeft(Map.empty[String, Bencode]) {
        case (r, BString(key)::(value:Bencode)::Nil) =>
          r + (key -> value)
        case f => throw new RuntimeException("Bencoding: could not parse dictionary. Failed on: " + f)
      })

    def str: Parser[BString] = (wholeNumber ^^ {_.toInt}) >> { n=> ":" ~> repN(n, anyChar)} ^^ {
      case x => BString(x.mkString(""))
    }
    def int: Parser[BInteger] = ("i" ~>  wholeNumber <~ "e") ^^ {case x => BInteger(x.toInt)}
    def list: Parser[BList] = ("l" ~>  rep(expr) <~ "e") ^^ {case x => BList(x)}
    def dict: Parser[BDictionary] = ("d" ~> rep(repN(2, expr)) <~ "e") ^^ dictContent
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
}


