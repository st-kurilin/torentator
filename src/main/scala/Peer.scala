package torentator.peer

import akka.actor.Props

case class DownloadBlock(pieceIndex: Int, offset: Int, length: Int)
case class BlockDownloaded(pieceIndex: Int, offset: Int, content: Seq[Byte])
case class DownloadingFailed(reason: String) extends RuntimeException

trait PeerPropsCreator {
  def props(trackerId: String, infoHash: Seq[Byte], connection: Props): Props
}

object Peer extends PeerPropsCreator {
  def props(trackerId: String, infoHash: Seq[Byte], connection: Props): Props = {
    val hs = handshakeMessage(trackerId, infoHash)
    Props(classOf[impl.Peer], hs, connection)
  }

  def handshakeMessage(peerIdStr: String, infoHash: Seq[Byte]): Seq[Byte] = {
    val peerId = peerIdStr.getBytes("ISO-8859-1")
    require(peerId.length == 20)
    require(infoHash.length == 20)
    val pstrlen = Seq(19).map(_.toByte)
    val pstr = "BitTorrent protocol".getBytes("ISO-8859-1")
    val reserved = Seq(0, 0, 0, 0, 0, 0, 0, 0).map(_.toByte)
    pstrlen ++ pstr ++ reserved ++ infoHash ++ peerId
  } 
}

object PeerMessage {
  sealed trait PeerMessage

  case object KeepAlive extends PeerMessage
  case object Choke extends PeerMessage
  case object Unchoke extends PeerMessage
  case object Interested extends PeerMessage
  case object NotInterested extends PeerMessage
  case class Have(pieceIndex: Int) extends PeerMessage
  case class Bitfield(data: Seq[Byte]) extends PeerMessage
  case class Request(index: Int, begin: Int, length: Int) extends PeerMessage
  case class Piece(index: Int, begin: Int, block: Seq[Byte]) extends PeerMessage
  case class Cancel(index: Int, begin: Int, length: Int) extends PeerMessage
  case class Port(port: Int) extends PeerMessage

  def unapply(x: Seq[Byte]): Option[PeerMessage] = {
    def takeInt(bs: Seq[Byte]) = {
      val (intRaw, rest) = bs.splitAt(4)
      val int = java.nio.ByteBuffer.wrap(intRaw.toArray).getInt
      (int, rest)
    }

    def readInt(bs: Seq[Byte]): Int = {
      takeInt(bs)._1
    }
    if (x.length < 4) return None
    if (x(0) == -1) return Some(KeepAlive)
    val (length, idAndData) = takeInt(x)
    val (id, data) = idAndData.splitAt(1)
    if (length == 0) Some(KeepAlive)
    else if (idAndData.length != length) {
      None
    } else {
      val parsed: PartialFunction[Int, PeerMessage] = {
        case 0 => Choke
        case 1 => Unchoke
        case 2 => Interested
        case 3 => NotInterested
        case 4 if data.length == 4 => new Have(readInt(data))
        case 5 => new Bitfield(data)
        case 6  if data.length == 12 =>
          val parsed = data.grouped(4).map(readInt).toArray
          val (index, begin, length) = (parsed(0), parsed(1), parsed(2))
          new Request(index, begin, length)
        case 7 if data.length > 8 =>
          val (indexAndBegin, block) = data.splitAt(8)
          val (index, begin) = takeInt(indexAndBegin)
          new Piece(index, readInt(begin), block)
        case 8 if data.length == 12 =>
          val parsed = data.grouped(4).map(readInt).toArray
          val (index, begin, length) = (parsed(0), parsed(1), parsed(2))
          new Cancel(index, begin, length)
        case 9 if data.length == 2 =>
          new Port(readInt(Seq(0, 0).map(_.toByte) ++ data))
      }
      parsed.lift(id(0))
    }
  }
  def toBytes(msg: PeerMessage): Seq[Byte] = {
    def intToByteArray(int: Int, size: Int = 4): Seq[Byte] =
      java.nio.ByteBuffer.allocate(4).putInt(int).array().drop(4 - size)
    def seq(ints: Int*) = ints.map(_.toByte)

    msg match {
      case KeepAlive => seq(0, 0, 0, 0)
      case Choke => seq(0, 0, 0, 1, 0)
      case Unchoke => seq(0, 0, 0, 1, 1)
      case Interested => seq(0, 0, 0, 1, 2)
      case NotInterested => seq(0, 0, 0, 1, 3)
      case Have(x) => seq(0, 0, 0, 5, 4) ++ intToByteArray(x)
      case Bitfield(data) => intToByteArray(data.length + 1) ++
        seq(5) ++ data
      case Request(index, begin, length) => seq(0, 0, 0, 13, 6) ++
        intToByteArray(index) ++ intToByteArray(begin) ++
        intToByteArray(length)
      case Piece(index, begin, block) => intToByteArray(block.length + 9) ++
        seq(7) ++ intToByteArray(index) ++ intToByteArray(begin) ++ block
      case Cancel(index, begin, length) => seq(0, 0, 0, 13, 8) ++
        intToByteArray(index) ++ intToByteArray(begin) ++
        intToByteArray(length)
      case Port(x) => seq(0, 0, 0, 3, 9) ++ intToByteArray(x, 2)
    }
  }
}

package impl {
  import akka.actor.{ Actor, ActorRef, AllForOneStrategy }
  import akka.actor.SupervisorStrategy._

  class Peer(handshakeMessage: Seq[Byte], connectionProps: Props) extends Actor with akka.actor.ActorLogging {
    import PeerMessage._
    import torentator.io
    import scala.language.postfixOps

    override val supervisorStrategy = AllForOneStrategy(loggingEnabled = false) {
      case io.ConnectionFailed(reason) => fail(reason); Escalate
      case e => Escalate
    }

    case class Assignment(requester: ActorRef, task: DownloadBlock)

    def fail(reason: String): Unit = {
      log.info("Peer failed: " + reason)
      context.stop(self)
      throw new DownloadingFailed(reason)
    }

    def assigned(c: DownloadBlock) = Some(Assignment(sender(), c))

    val connection = context.actorOf(connectionProps, "connection")
    connection ! io.Send(handshakeMessage)

    def receive = handshaking(None)

    def handshaking(assignment: Option[Assignment]): Receive = {
      case c: DownloadBlock =>
        context become handshaking(assigned(c))
      case io.Received(hsResponce) =>
        context become chocked(assignment)
    }

    def chocked(assignment: Option[Assignment]): Receive = {
      case c: DownloadBlock =>
        context become chocked(assigned(c))
      case io.Received(c) => c match {
        case PeerMessage(m) => m match {
          case Unchoke => context become unchocked(assignment)
          case _ =>
        }
      }
    }

    def chockedWithassignment(assignment: Assignment): Receive = {
      case io.Received(c) => c match {
        case PeerMessage(m) => m match {
          case Unchoke => context become download(assignment)
          case _ =>
        }
      }
    }

    def unchocked(assignment: Option[Assignment]): Receive = assignment match {
      case Some(a) => download(a)
      case None => {
        case c: DownloadBlock =>
          context become unchocked(assigned(c))
      }
    }
    def download(assignment: Assignment): Receive = {
      val Assignment(requester: ActorRef, DownloadBlock(pieceIndex, offset, length)) = assignment

      def downloading(buffer: Seq[Byte] = Seq.empty): Receive = {
        case io.Received(bs) => buffer ++ bs match {
          case PeerMessage(m) => m match {
            case Piece(index, begin, block) =>
              log.debug("got i:{} b:{} size:${}", index, begin, block.length)
              requester ! BlockDownloaded(index, begin, block)
              context become unchocked(None)
            case _ => context become downloading()
          }
          
          case newBuffer => context become downloading(newBuffer)
        }
      }
      send(Request(pieceIndex, offset, length))
      downloading()
    }

    def send(msg: PeerMessage) = connection ! io.Send(toBytes(msg))
  }
}
