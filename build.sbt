scalaVersion := "2.11.5"

libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2"


resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
 
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.9")

libraryDependencies += "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"

libraryDependencies += "junit" % "junit" % "4.10" % "test"
