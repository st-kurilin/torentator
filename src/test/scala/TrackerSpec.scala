package torentator

import org.scalatest._

class TrackerSpec extends FlatSpec with Matchers {
  import Bencoding._
  import Tracker._
  import util.{Success, Failure}

  //https://wiki.theory.org/BitTorrent_Tracker_Protocol
  "Tracker" should "parse shorten peer" in {
    val peer = newPeer(Array(0x0a, 0x0a, 0x0a, 0x05, 0x00, 0x80))

    val parsed = decoded(peer)

    assert (parsed === new java.net.InetSocketAddress("10.10.10.5", 128))
  }


  it should "parse announce with one peer" in {
    val peer = newPeer(Array(0x0a, 0x0a, 0x0a, 0x05, 0x00, 0x80))
    val content = new String("d8:intervali21e5:peers6:" + bencoded(peer) + "e")
    val parsed = parseAnnounce(content)

    parsed match {
      case Success(announce) =>
        assert (announce.interval === 21)
        assert (announce.peers === Set(decoded(peer)))
      case Failure(f) => fail(f.getStackTrace.mkString("\n"))
    }
  }

  it should "parse announce with several peers" in {
    val peerA = newPeer(Array(0x0a, 0x0a, 0x0a, 0x05, 0x00, 0x80))
    val peerB = newPeer(Array(0x0b, 0x0a, 0x0b, 0x0c, 0x01, 0x80))
    val content = new String("d8:intervali21e5:peers12:" + bencoded(peerA) + bencoded(peerB) + "e")
    val parsed = parseAnnounce(content)

    parsed match {
      case Success(announce) =>
        assert (announce.interval === 21)
        assert (announce.peers === Set(decoded(peerA), decoded(peerB)))
      case Failure(f) => fail(f.getStackTrace.mkString("\n"))
    }
  }

  it should "get announce from tracker" in {
    val manifest = Manifest(new java.io.File("./src/test/resources/sample.single.http.torrent"))

    manifest.flatMap(announce(_)) match {
        case Success(announce) =>
            assert (announce.interval !== 0)
            announce.peers should not be empty
        case f => fail(f.toString)
    }
  }

  type Peer = Seq[Byte]
  def newPeer(shortForm: Seq[Int]) = shortForm.map(_.toByte)
  def bencoded(p: Peer) = new String(p.toArray, "ISO-8859-1")
  def decoded(p: Peer) = parseHostAndPort(p)

}