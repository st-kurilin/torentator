package torentator

object Manifest {
    import Bencoding._
    def apply(encoded: Bencode): Manifest = {
        encoded match {
            case BDictionary(m) =>
                val announce = m("announce") match {case BString(s) => s}
                m("info") match {
                    case BDictionary(info) => 
                        (info.get("piece"), info.get("length")) match {
                            case (Some(BInteger(piece)), Some(BInteger(length))) => 
                                new SingleFileManifest(new java.net.URL(announce), piece, length)        
                        }
                        
                }
        }
    }   
}

sealed trait Manifest {
    def announce: java.net.URL
    def pieceLenght: Long
}
case class SingleFileManifest(announce: java.net.URL, pieceLenght: Long, length: Long) extends Manifest
case class MultiFileManifest(announce: java.net.URL, pieceLenght: Long, paths: List[String]) extends Manifest {
    require(!paths.isEmpty)
}    


