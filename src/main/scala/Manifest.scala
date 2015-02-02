package torentator

object Manifest {
    import Bencoding._
    def apply(encoded: Bencode, hash: Seq[Int]): Manifest = {
        encoded match {
            case BDictionary(m) =>
                val announce = m("announce") match {case BString(s) => s}
                m("info") match {
                    case BDictionary(info) => 
                        (info.get("name"), info.get("piece"), info.get("length"), info.get("path")) match {
                            case (Some(BString(name)),
                                Some(BInteger(piece)),
                                Some(BInteger(length)),
                                None) => 
                                new SingleFileManifest(name, new java.net.URL(announce), hash, piece, length)

                            case (Some(BString(name)), 
                                Some(BInteger(piece)),
                                None,
                                Some(BList(l))) => 
                                val paths = l.map{case BString(s) => s}
                                new MultiFileManifest(name, new java.net.URL(announce), hash, piece, paths)
                        }
                        
                }
        }
    }   
}

sealed trait Manifest {
    def name: String
    def announce: java.net.URL
    def pieceLenght: Long
    def hash: Seq[Int]
}
case class SingleFileManifest(name: String, announce: java.net.URL, hash: Seq[Int], pieceLenght: Long, length: Long) extends Manifest
case class MultiFileManifest (name: String, announce: java.net.URL, hash: Seq[Int], pieceLenght: Long, paths: Seq[String]) extends Manifest {
    require(!paths.isEmpty)
}    


