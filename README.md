Torentator
===========

Yet another torrent client implementation. The only motivation for doing it is to do **scala/akka** for real. Can be used as code sample, but should never be considered as production ready version.

DONE
-----
* Bencoding parsing and serialisation
* Torrent manifest parsing
* Getting announce from tracker throw TCP
* Piece downloading using Peer Protocol
* Piece hash code check
* Flushing downloaded pieces to file


TODO
-----
* Multifile torrents support
* Seeding
* UDP support for trackers
* Announce list usage if "announce tracker" is not available
* Resumable downloadings
* CLI/GUI
