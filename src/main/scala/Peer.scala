package torentator.peer

import akka.actor.{ Actor, ActorRef, Props, AllForOneStrategy, PoisonPill }
import akka.actor.SupervisorStrategy._


object Peer {
  def props(trackerId: String, infoHash: Seq[Byte], connection: Props) = {
    val hs = handshakeMessage(trackerId, infoHash)
    Props(classOf[Peer], hs, connection)
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

  case class DownloadPiece(pieceIndex: Int, begin: Int, length: Long)
  case class BlockDownloaded(pieceIndex: Int, offset: Long, content: Seq[Byte])
  case class PieceDownloaded(pieceIndex: Int)
  case class DownloadingFailed(reason: String) extends RuntimeException
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
      val int = java.nio.ByteBuffer.wrap(intRaw.toArray).getInt()
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
  def toBytes(x: PeerMessage): Seq[Byte] = {
    def intToByteArray(int: Int, size: Int = 4): Seq[Byte] = 
      java.nio.ByteBuffer.allocate(4).putInt(int).array().drop(4 - size)
    def seq(ints: Int*) = ints.map(_.toByte)

    x match {
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

class Peer(handshakeMessage: Seq[Byte], connectionProps: Props) extends Actor {
  import Peer._
  import PeerMessage._
  import torentator.io
  import scala.language.postfixOps
  import scala.concurrent.duration._
  import context.dispatcher

  override val supervisorStrategy = AllForOneStrategy(loggingEnabled = false) {
    case io.ConnectionFailed(reason) => throw new DownloadingFailed(reason)
    case e => Escalate
  }

  val Tick = "tick"
  var quality = 1000
  val connection = context.actorOf(connectionProps, "connection")

  var hsResponce: Option[Seq[Byte]] = None
  connection ! io.Send(handshakeMessage)    

  var assigment: Option[(ActorRef, DownloadPiece)] = None
  var choked = true

  context.system.scheduler.schedule(1 seconds, 3 seconds, self, Tick)

  def receive = {
    case c: DownloadPiece =>
      assigment = Some((sender() -> c))
      quality = 5
    case io.Received(c) =>
      hsResponce = Some(c)
      quality += 1
      context become handshaked
      //send(Interested)
    case Tick if quality > 0 => quality -= 1
    case Tick =>
      context become {case _ => }
      throw new DownloadingFailed("Failed due timeout before handshake")   

  }

  def handshaked: Receive = {
    case io.Received(c) => c match {
      case PeerMessage(m) => 
        m match {
          case Unchoke => 
            choked = false
            quality += 2
            assigment match {
              case Some ( (_, DownloadPiece(_, begin, _))) => 
                download(begin)
              case _ =>
            }
            
            
          case c: DownloadPiece =>
            assigment = Some((sender() -> c))
            quality = 5
            if (!choked) assigment match {
              case Some ( (_, DownloadPiece(_, begin, _))) => 
                download(begin)
              case _ =>
            }
          //case m: KeepAlive =>
          case m: Bitfield => 
          case Choke => quality -= 2
          case KeepAlive =>
          case m =>  println("RECEIVED MSG: " + m) 
        }
    }
    case Tick if quality > 0 => quality -= 1
    case Tick =>
      context become {case _ => }
      throw new DownloadingFailed("Failed due timeout after handshake, but before download started")   
  }

  var data = Seq.empty[Byte]
  var old: Option[Seq[Byte]] = None
  var done = false
  var prevDownloaded = -1
  def download(downloaded: Int): Unit = {
    require(prevDownloaded <= downloaded)
    val (listener, assigment) = this.assigment.get

    def handleMsd(msg : PeerMessage) = msg match {
      case KeepAlive =>
      case Choke => 
      case Piece(index, begin, block) if begin + block.length > downloaded =>
        println(s"got i:${index} b:${begin} size:${block.length}")
        data = data ++ block
        quality += 2
        listener ! BlockDownloaded(index, begin, block)
        download(begin + block.length)
      case m: Piece =>
      case b: Bitfield => println("received Bitfield")
      case _ => println(s"startDownload got msg: ${msg}")
    }
    require(downloaded <= assigment.length)
    if (downloaded == assigment.length) {
      listener ! PieceDownloaded(assigment.pieceIndex)
      done = true
      quality += 2
      println("DONE")
      context become { case _ => }
    } else {
      send(Request(assigment.pieceIndex, downloaded, java.lang.Math.min(16384, (assigment.length - downloaded).toInt)))
      context become {
        case io.Received(bs) => bs match {
          case PeerMessage(m) => handleMsd(m)
          case _ => old match {
            case Some(o) =>
              (o ++ bs) match {
                case PeerMessage(m) =>
                  handleMsd(m)
                  old = None
                case x =>
                  old = Some(x)
                  //println(s"squashing during ${assigment.index}")
              }
            case None => 
              old = Some(bs)
            }
        }
        case Tick if quality >= 0 =>
          quality-= 1
          println(s"tick piece: ${assigment.pieceIndex}; downloaded: ${downloaded}; quality: ${quality}; // ${self}")
        case Tick if !done & quality < 0 =>
          println(s"tick piece: ${assigment.pieceIndex}; quality: ${quality}; downloaded: ${downloaded}; ask for replacement  // ${self}")
          listener ! DownloadingFailed("Quality is too low") 
          context become { case _ => }
        case Tick => println(s"tick piece: ${assigment.pieceIndex}; quality: ${quality}; done: ${done}")
      }
    }
  }
  def send(msg: PeerMessage) = connection ! io.Send(toBytes(msg))
}



