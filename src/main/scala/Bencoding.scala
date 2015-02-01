package torentator

object Bencoding {    
    sealed trait Bencode
    case class BString(value: String) extends Bencode
    case class BInteger(value: Integer) extends Bencode
    case class BList(value: Seq[Bencode]) extends Bencode
    case class BDictionary(value: Map[String, Bencode]) extends Bencode

    import scala.util.parsing.combinator._

    object Parser extends JavaTokenParsers {
        def str: Parser[BString] = (wholeNumber ^^ {_.toInt}) >> {n=> ":" ~> """.*""".r} ^^ {case x => BString(x.toString)}
        def int: Parser[BInteger] = ("i" ~>  wholeNumber <~ "e") ^^ {case x => BInteger(x.toInt)}
        def expr: Parser[Bencode] = str | int
        def apply(str: String) = {
            parseAll(expr, str) match {
                case Success(r, _) => util.Success(r)
                case Failure(r, _) => util.Failure(new RuntimeException(r))
                case Error(r, _) => util.Failure(new RuntimeException(r))
            }
            
        }
    }
    

    def parse(str: String): util.Try[Bencode] = {
        Parser.apply(str)
    }
}


