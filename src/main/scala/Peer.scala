package torentator

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.{InetSocketAddress => Address}

object Peer {
  def props(id: String, manifest: Manifest, remote: Address) = 
    Props(classOf[Peer], id, manifest, remote)

  def handshakeMessage(peerId: Seq[Byte], manifest: Manifest): Seq[Byte] = {
    require(peerId.length == 20)
    require(manifest.hash.length == 20)
    val pstrlen = Seq(19).map(_.toByte)
    val pstr = "BitTorrent protocol".getBytes("ISO-8859-1")
    val reserved = Seq(0, 0, 0, 0, 0, 0, 0, 0).map(_.toByte)
    val infoHash = manifest.hash
    pstrlen ++ pstr ++ reserved ++ infoHash ++ peerId
  }
}

object PeerMessage {
  sealed class PeerMessage

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



  def unapply(x: ByteString): Option[PeerMessage] = {
    def takeInt(bs: ByteString) = {
      val (intRaw, rest) = bs.splitAt(4)
      val int = java.nio.ByteBuffer.wrap(intRaw.toArray).getInt()
      (int, rest)
    }

    def readInt(bs: ByteString): Int = {
      takeInt(bs)._1
    }
    val (length, idAndData) = takeInt(x)
    if (length == 0) Some(KeepAlive)
    else {
      val (id, data) = idAndData.splitAt(1)
      require(data.length == length - 1)  
      Some(id(0) match {
        case 0 => Choke
        case 1 => Unchoke
        case 2 => Interested
        case 3 => NotInterested
        case 4 => new Have(readInt(data))
        case 5 => new Bitfield(data)
        case 6 => 
          require(data.length == 13)
          val parsed = data.grouped(4).map(readInt(_)).toArray
          val (index, begin, length) = (parsed(0), parsed(1), parsed(2))
          new Request(index, begin, length)
        case 7 =>
          val (indexAndBegin, block) = data.splitAt(8)
          val (index, begin) = takeInt(indexAndBegin)
          new Piece(index, readInt(begin), block)
        case 8 =>
          val parsed = data.grouped(4).map(readInt(_)).toArray
          val (index, begin, length) = (parsed(0), parsed(1), parsed(2))
          new Cancel(index, begin, length)
        case 9 =>
          require(data.length == 3)
          new Port(readInt(data))
      })
    }
  }
}

sealed trait NetworkMessage
case object ConnectFailed
case object Connected
case object ConnectionClosed
case object WriteFailed

class Peer(id: String, manifest: Manifest, remote: Address) extends Actor {
  val client = context.actorOf(Props(classOf[NetworkConnection], remote, self), "client-" + remote.getHostString)

  var hsResponce: Option[Seq[Byte]] = None

  def receive = {
    case Connected =>
      val hs = Peer.handshakeMessage(id.getBytes("ISO-8859-1"), manifest)
      sender() ! ByteString(hs.toArray)
    case c: ByteString =>
      hsResponce = Some(c)
      context become handshaked
  }

  def handshaked: Receive = {
    case c: ByteString => c match {
      case PeerMessage(m) => println("RECEIVED MSG: " + m)
    }
    case x => println(x)
  }
}


class NetworkConnection(remote: Address, listener: ActorRef) extends Actor {
  import Tcp.{Connected => TcpConnected, _}
  import context.system
 
  IO(Tcp) ! Connect(remote)
 
  def receive = {
    case CommandFailed(_: Connect) =>
      listener ! "connect failed"
      context stop self
 
    case c @ TcpConnected(remote, local) =>
      listener ! c
      val connection = sender()
      connection ! Register(self)
      context become {
        case data: ByteString =>
          connection ! Write(data)
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          listener ! "write failed"
        case Received(data) =>
          listener ! data
        case "close" =>
          connection ! Close
        case _: ConnectionClosed =>
          listener ! "connection closed"
          context stop self
      }
  }
}