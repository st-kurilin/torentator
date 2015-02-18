package torentator

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.{InetSocketAddress => Address}

object Peer {
  def props(id: String, manifest: Manifest, connection: Props) = 
    Props(classOf[Peer], id, manifest, connection)

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



  def unapply(x: ByteString): Option[PeerMessage] = {
    def takeInt(bs: ByteString) = {
      val (intRaw, rest) = bs.splitAt(4)
      val int = java.nio.ByteBuffer.wrap(intRaw.toArray).getInt()
      (int, rest)
    }

    def readInt(bs: ByteString): Int = {
      takeInt(bs)._1
    }
    if (x(0) == -1) return Some(KeepAlive)
    val (length, idAndData) = takeInt(x)
    val (id, data) = idAndData.splitAt(1)
    if (length == 0) Some(KeepAlive)
    else if (idAndData.length != length) None
    else {
      val parsed: PartialFunction[Int, PeerMessage] = {
        case 0 => Choke
        case 1 => Unchoke
        case 2 => Interested
        case 3 => NotInterested
        case 4 if data.length == 4 => new Have(readInt(data))
        case 5 => new Bitfield(data)
        case 6  if data.length == 12 => 
          val parsed = data.grouped(4).map(readInt(_)).toArray
          val (index, begin, length) = (parsed(0), parsed(1), parsed(2))
          new Request(index, begin, length)
        case 7 if data.length > 8 =>
          val (indexAndBegin, block) = data.splitAt(8)
          val (index, begin) = takeInt(indexAndBegin)
          new Piece(index, readInt(begin), block)
        case 8 if data.length == 12 =>
          val parsed = data.grouped(4).map(readInt(_)).toArray
          val (index, begin, length) = (parsed(0), parsed(1), parsed(2))
          new Cancel(index, begin, length)
        case 9 if data.length == 2 =>
          new Port(readInt(Seq(0, 0).map(_.toByte) ++ data))
      }
      parsed.lift(id(0))
    }
  }

  object ByteString {
    def intToByteArray(int: Int, size: Int = 4): Seq[Byte] = 
      java.nio.ByteBuffer.allocate(4).putInt(int).array().drop(4 - size)
    def seq(ints: Int*) = ints.map(_.toByte)

    def unapply(x: PeerMessage): Option[ByteString] = {
      val known: PartialFunction[PeerMessage, Seq[Byte]] = {
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
      known.andThen(res => akka.util.ByteString(res.toArray)).lift(x)
    }
  }
}

class Peer(id: String, manifest: Manifest, connectionProps: Props) extends Actor {
  import PeerMessage.{ByteString => PByteString, _}
  val connection = context.actorOf(connectionProps, "connection")

  var hsResponce: Option[Seq[Byte]] = None
  val hs = Peer.handshakeMessage(id.getBytes("ISO-8859-1"), manifest)
  connection ! ByteString(hs.toArray)    

  def receive = {
    case c: ByteString =>
      hsResponce = Some(c)
      context become handshaked
      send(Interested)
    case x =>
      
      println("???5" + x)
  }

  def handshaked: Receive = {
    case c: ByteString => c match {
      case PeerMessage(m) => 
      println("RECEIVED MSG: " + m)
      m match {
        case Unchoke => send(Request(1, 0, 1024))
        case _ => 
      }
      
    }
    case x => println(x)
  }
  def send(msg: PeerMessage) = msg match {
    case PByteString(bytes) => connection ! bytes
  }
}



class NetworkConnection(remote: Address) extends Actor {
 
  import Tcp._
  import context.system
  val listener = context.parent
  IO(Tcp) ! Connect(remote)
 
  def receive = connecting(List.empty)

  def connecting(msgQueue: List[Any]) : Receive = {
    case CommandFailed(_: Connect) =>
      context stop self
      throw new RuntimeException("connect failed")
 
    case c @ Connected(remote, local) =>
      val connection = sender()
      connection ! Register(self)
      context become connected(connection)
      for (m <- msgQueue) self ! m
    case x =>
      context become connecting(x :: msgQueue)
  }

  def connected(connection: ActorRef): Receive = {
    case data: ByteString => 
      connection ! Write(data)
    case CommandFailed(w: Write) =>
      // O/S buffer was full
      throw new RuntimeException("write failed")
    case Received(data) =>
      listener ! data
    case "close" =>
      connection ! Close
    case _: ConnectionClosed =>
      listener ! "connection closed"
      context stop self
  }
}
