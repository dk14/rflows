name := "rflows"

scalaVersion := "2.11.6"

exportJars := true

resolvers += Resolver.sonatypeRepo("releases")

javaOptions += "-Xmx32M"

organization := "com.github.dk14"

libraryDependencies += "io.spray"                     %% "spray-routing-shapeless2" % "1.3.2"

libraryDependencies += "com.typesafe.akka"            %% "akka-actor"               % "2.3.6"

libraryDependencies += "com.typesafe.akka"            %% "akka-slf4j"               % "2.3.6"

libraryDependencies += "nl.grons"                     %% "metrics-scala"            % "3.5.1_a2.3"

libraryDependencies += "org.scala-lang"               %  "scala-reflect"            % scalaVersion.value

libraryDependencies += "org.scalatest"                %% "scalatest"                % "3.0.0-M7" % "test"

libraryDependencies += "com.lihaoyi"                  %% "utest"                    % "0.3.1"

