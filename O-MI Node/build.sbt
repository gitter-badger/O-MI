
val scalaBuildVersion = "2.11.2"

scalaVersion := scalaBuildVersion

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8")

// STM
libraryDependencies += ("org.scala-stm" %% "scala-stm" % "0.7")

// SPRAY
libraryDependencies ++= {
  val akkaV = "2.3.6"
  val sprayV = "1.3.2"
  Seq(
    "io.spray"            %%  "spray-can"     % sprayV,
    "io.spray"            %%  "spray-routing" % sprayV,
    "io.spray"            %%  "spray-testkit" % sprayV  % "test",
    "com.typesafe.akka"   %%  "akka-actor"    % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"   % "2.3.11" % "test"
  )
}

//slick
libraryDependencies ++= List(
"com.typesafe.slick"  %%  "slick" % "2.1.0",
"org.slf4j" % "slf4j-nop" % "1.6.4",
"org.xerial" % "sqlite-jdbc" % "3.7.2"
)

Revolver.settings

// Eclipse
EclipseKeys.withSource := true

