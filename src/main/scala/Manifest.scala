package torentator

object Manifest {
  import Bencoding._
  import util.Success
  import util.Failure
  import util.Try
  def apply(location: java.io.File): util.Try[Manifest] = {
    val content = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(location.getPath))
    parse(new String(content, "ISO-8859-1")) flatMap { encoded =>
      Manifest(encoded, infoHash(content))
    }
  }

  private def f(msg: String) = {
    Failure(new RuntimeException(msg))
  }

  def flatten[T](xs: Seq[Try[T]]): Try[Seq[T]] = {
    val (ss: Seq[Success[T]]@unchecked, fs: Seq[Failure[T]]@unchecked) =
    xs.partition(_.isSuccess)

    if (fs.isEmpty) {
      Success(ss map (_.get))
    } else {
      Failure[Seq[T]](fs(0).exception) // Only keep the first failure
    }
  }

  def apply(encoded: Bencode, hash: Seq[Byte]): util.Try[Manifest] = {
    def decodeAnnounce(encoded: Bencode) = encoded match {
      case BString(s) => Success(s)
      case e => f(s"Manifest 'info' expected to be string, but [${e}] found.")
    }
    val hashSize = 20
    def decodeInfo(announce: String, encoded: Bencode) = encoded match {
      case BDictionary(info) =>
        (info.get("name"),
          info.get("piece length"),
          info.get("length"),
          info.get("files"),
          info.get("pieces")) match {
          case (Some(BString(name)),
            Some(BInteger(piece)),
            Some(BInteger(length)),
            None,
            Some(BinaryString(hashes))) if hashes.size % hashSize == 0 =>
            val pieceHashes = hashes.grouped(hashSize).toSeq.map(_.toSeq)
            Success(new SingleFileManifest(name, new java.net.URI(announce),
              hash, pieceHashes, piece, length))
          case (Some(BString(name)),
            Some(BInteger(piece)),
            None,
            Some(BList(filesList)),
            _) =>
            decodeFiles(filesList) map { case f =>
              new MultiFileManifest(name, new java.net.URI(announce), hash, piece, f)
            }
          case (name, pieceLength, length, files, pieceHashes) =>
            f(s"""Can not determine structure of 'manifest.info':
                name: ${name}, pieceLength: ${pieceLength},
                length: ${length}, files: ${files}, pieceHashes: ${pieceHashes}
                available keys: ${info.keys}""")
        }
      case e => f(s"Manifest 'info' expected to be dictionary, but [${e}] found.")
    }
    def decodeFiles(encoded: Seq[Bencode]) = {
      flatten(encoded map {
        case BDictionary(m) => (m.get("length"), m.get("path")) match {
          case (Some(BInteger(length)), Some(BList(path))) =>
            flatten(path.map {
              case BString(e) => Success(e)
              case e => Failure(new RuntimeException("""'manifest.info.files.path'
                elements expected to be strings but [${e}] found"""))
            }) map { l =>
              l.foldLeft("") {
                case (r, e) if r.isEmpty => e
                case (r, e) => s"${r}/${e}"
              }
            } map { p =>
              (length, p)
            }
          case e => f(s"""File in 'manifest.info.files' expected to be dictinary
            with 'length' and 'path' but [${e}] found.""")
        }
        case e => f(s"""File in 'manifest.info.files'
          expected to be dictinary but ${e} found.""")
      })
    }
    encoded match {
      case BDictionary(m) =>
        decodeAnnounce(m("announce")) flatMap {
          announce => decodeInfo(announce, m("info"))
        }
      case e => f(s"Manifest expected to be dictionary, but [${e}] found.")
    }
  }
}

sealed trait Manifest {
  def name: String
  def announce: java.net.URI
  def pieceLength: Long
  def hash: Seq[Byte]
}
case class SingleFileManifest(name: String, announce: java.net.URI,
  hash: Seq[Byte], pieces: Seq[Seq[Byte]], pieceLength: Long, length: Long) extends Manifest
case class MultiFileManifest (name: String, announce: java.net.URI,
  hash: Seq[Byte], pieceLength: Long, files: Seq[(Long, String)]) extends Manifest

