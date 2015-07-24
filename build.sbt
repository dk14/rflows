import CoverallsPlugin.CoverallsKeys._

name := "rflows"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.6"

exportJars := true

resolvers += Resolver.sonatypeRepo("releases")

javaOptions += "-Xmx32M"

libraryDependencies += "io.spray"                     %% "spray-routing-shapeless2" % "1.3.2"
libraryDependencies += "com.typesafe.akka"            %% "akka-actor"               % "2.3.6"
libraryDependencies += "com.typesafe.akka"            %% "akka-slf4j"               % "2.3.6"
libraryDependencies += "nl.grons"                     %% "metrics-scala"            % "3.5.1_a2.3"
libraryDependencies += "org.scala-lang"               %  "scala-reflect"            % scalaVersion.value
libraryDependencies += "org.scalatest"                %  "scalatest_2.11"           % "3.0.0-M7" % "test"

coverallsToken := "QkzIoJtqyx2rfphcKrL6q6gDFmTtie7M8"
