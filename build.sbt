scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.typesafe" % "config" % "1.2.1"
  ,"com.typesafe.akka" %% "akka-stream-experimental" % "1.0-M4"
  ,"com.typesafe.akka" %% "akka-slf4j" % "2.3.9"
  ,"org.slf4j" % "jul-to-slf4j" % "1.7.7"
  ,"me.moocar" % "logback-gelf" % "0.12"
  ,"ch.qos.logback" % "logback-classic" % "1.1.2"
  ,"io.spray" %% "spray-routing" % "1.3.2"
  ,"io.spray" %% "spray-can" % "1.3.2"
  ,"io.spray" %% "spray-json" % "1.3.1"
  ,"ru.dgis.casino" %% "commons-config" % "1.0.0-SNAPSHOT"
  ,"ru.dgis.casino" %% "commons-spray" % "1.1.0-SNAPSHOT"
  ,"ru.dgis.casino" %% "commons-akka" % "1.1.0-SNAPSHOT"
  ,"com.typesafe.akka" %% "akka-testkit" % "2.3.7"
  ,"org.scalatest" %% "scalatest" % "2.2.4" % "test"
)

