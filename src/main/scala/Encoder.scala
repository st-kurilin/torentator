package torentator.encoding

/**Contains all util function that might be used for general work with string and byte sequencies.*/

object Encoder {
  def strToBytes(s: String): Seq[Byte] = s.getBytes("ISO-8859-1")

  def bytesToStr(b: Seq[Byte]) = new String(b.toArray, "ISO-8859-1")

  def hash(data: Seq[Byte]): Seq[Byte] =
    java.security.MessageDigest.getInstance("SHA1").digest(data.toArray)

  def urlEncode(hash: Seq[Byte]): String = {
    val ints = hash.map(_.toInt & 0xFF)
    val asIs = Set('-', '.', '_') map (_.toInt)
    val asIsRanges = Set(
      '0'.toInt to '9'.toInt,
      'a'.toInt to 'z'.toInt,
      'A'.toInt to 'Z'.toInt)

    val out = new StringBuilder();
    for (letter <- ints) {
      if (asIs.contains(letter) || asIsRanges.exists(_.contains(letter))) {
        out.append(letter.toChar);
      } else {
        var s = Integer.toHexString(letter).toUpperCase()
        if (s.length() == 1) {
          s = "0" + s;
        }
        out.append("%" + s.substring(s.length() - 2, s.length()))
      }
    }
    out.toString();
  }

  def parseByteArray(s: String): Seq[Byte] = {
    def parseHex(x: String): Byte = Integer.parseInt(x, 16).toByte
    val o: Tuple2[String, List[Byte]] = (("", List.empty[Byte]))
    val (_, r) = s.foldRight(o) { (x, r) =>
      val (cur, res) = r
      if (cur.isEmpty) (x.toString, res)
      else ("", parseHex(x + cur) :: res)
    }
    r
  }
}
