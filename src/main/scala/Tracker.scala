package torentator.tracker

import java.net.InetSocketAddress
import torentator.manifest.Manifest
import akka.actor.Props

/** Tracker actor. Tracker might be used to get announce that contains peers information. */

case object RequestAnnounce
case class AnnounceReceived(announce: Announce)

case class Announce(interval: Long, peers: Set[InetSocketAddress])

object Tracker {
  def props(manifest: Manifest) = Props(classOf[impl.Tracker], manifest)
  val id = "ABCDEFGHIJKLMNOPQRST"
}

package impl {
  import util.{Success, Failure}
  import torentator.bencoding._
  import torentator.encoding.Encoder._
  import scala.concurrent.Future

  object TrackerImpl {
    def get(url: String, attempt: Int = 0): String = {
      try {
        scala.io.Source.fromURL(url, "ISO-8859-1").mkString
      } catch {
        case e: Throwable if attempt > 10 => throw e
        case e: Throwable =>
          Thread.sleep(100)
          get(url, attempt + 1)
      }
    }

    def announce(manifest: Manifest)(implicit ec: scala.concurrent.ExecutionContext): Future[Announce] = Future {
      val hash = urlEncode(manifest.infoHash)
      val rest = "port=6881&uploaded=0&downloaded=0&left=727955456&event=started&numwant=100&no_peer_id=1&compact=1"

      // sample http://torrent.ubuntu.com:6969/announce
      //?info_hash=%16%19%EC%C97%3C69%F4%EE%3E%26%168%F2%9B3%A6%CB%D6
      //&peer_id=ABCDEFGHIJKLMNOPQRST&port=6881&uploaded=0&downloaded=0&left=727955456
      //&event=started&numwant=100&no_peer_id=1&compact=1
      val url = s"${manifest.announce}?info_hash=$hash&peer_id=${Tracker.id}&$rest"
      val content = get(url)
      parseAnnounce(content) match {
        case Success(r) => r
        case Failure(f) => throw f
      }
    }

    def parseAnnounce(content: String) = {
      Bencode.parse(content) flatMap {
        case BDictionary(m) => (m.get("interval"), m.get("peers")) match {
            case (Some(BInteger(interval)), Some(BinaryString(peersInShortForm))) =>
              val peers = peersInShortForm.grouped(6).map(a => parseHostAndPort(a)).toSet
              Success(new Announce(interval, peers))
            case f => Failure(new RuntimeException(
              s"Could not parse announce. Short peers description expected, but [$f] found."))
        }
        case f => Failure(new RuntimeException(
              s"Could not parse announce. Dictionary expected, but [$f] found."))
      }
    }

    def parseHostAndPort(shortForm: Seq[Byte]): InetSocketAddress = {
      def parseInt(b1: Byte, b2: Byte) = java.nio.ByteBuffer.wrap(Array(b1, b2)).getChar.toInt
      require(shortForm.size == 6)
      val ipNumbers = for (number <- 1 to 4) yield parseInt(0, shortForm(number - 1))
      val ip = ipNumbers.mkString(".")
      val port = parseInt(shortForm(4), shortForm(5))
      new InetSocketAddress(ip, port)
    }
  }

  import akka.actor.Actor

  class Tracker(manifest: Manifest) extends Actor {
    import TrackerImpl._
    import context.dispatcher

    def receive: Receive = {
      case RequestAnnounce =>
        val requester = sender()
        announce(manifest) onSuccess {case a =>
          requester ! AnnounceReceived(a)
        }
    }
  }
}

