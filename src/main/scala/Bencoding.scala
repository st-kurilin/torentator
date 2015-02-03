package torentator

object Bencoding {    
    sealed trait Bencode
    case class BString(value: String) extends Bencode
    case class BInteger(value: Long) extends Bencode
    case class BList(value: Seq[Bencode]) extends Bencode
    case class BDictionary(value: Map[String, Bencode]) extends Bencode

    import scala.util.parsing.combinator._

    object Parser extends JavaTokenParsers {
        override val skipWhitespace = false
        def str: Parser[BString] = (wholeNumber ^^ {_.toInt}) >> { n=> ":" ~> repN(n, """\S""".r | whiteSpace)} ^^ {
            case x => BString(x.mkString(""))
        }
        def int: Parser[BInteger] = ("i" ~>  wholeNumber <~ "e") ^^ {case x => BInteger(x.toInt)}
        def list: Parser[BList] = ("l" ~>  rep(expr) <~ "e") ^^ {case x => BList(x)}
        def dict: Parser[BDictionary] = ("d" ~> rep(repN(2, expr)) <~ "e") ^^ {case x => 
            BDictionary(x.foldLeft(Map.empty[String, Bencode]) {
                case (r, BString(key)::(value:Bencode)::Nil) =>
                    r + (key -> value)
            })
        }
        def expr: Parser[Bencode] = str | int | list | dict
        def apply(str: String) = {
            parseAll(expr, str) match {
                case Success(r, _) => util.Success(r)
                case Failure(r, o) => println(s">>>failure ${r} -- ${o.pos}"); util.Failure(new RuntimeException(r))
                case Error(r, o) => println(s">>>error ${r} -- ${o}"); util.Failure(new RuntimeException(r))
            }
            
        }
    }
    

    def parse(str: String): util.Try[Bencode] = {
        Parser.apply(str)
    }

    def hash(str: String) = {
        val startIndex = str.indexOf("info") + "info".length
        val endIndex = str.length - "e".length
        val infoValue = str.substring(startIndex, endIndex)

        java.security.MessageDigest.getInstance("SHA1").digest(infoValue.getBytes)
    }
}


