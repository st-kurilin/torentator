package torentator.tracker

import org.scalatest._
import scala.concurrent.ExecutionContext.Implicits.global

class TrackerSpec extends FlatSpec with Matchers {
  //TODO: do not test TrackerImpl directly. Do it only throw API.
  import TrackerImpl._
  import util.{Try, Success, Failure}

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
    val manifest = torentator.manifest.Manifest.read(java.nio.file.Paths.get("./src/test/resources/sample.single.http.torrent")).get
    futureToTry(announce(manifest)) match {
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

  import scala.concurrent._
  import duration._
  def futureToTry[T](f: Future[T]): Try[T] = Await.ready(f, Duration(10, SECONDS)).value.get

}