import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

name := "zeno"

lazy val zeno = crossProject(JSPlatform, JVMPlatform)
  .in(file("."))
  .settings(
    name := "zeno",
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.github.scopt" %% "scopt" % "3.7.0",
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
      "io.netty" % "netty-all" % "4.1.25.Final",
      "org.scala-js" %% "scalajs-library" % scalaJSVersion % "provided",
      "org.scala-js" %% "scalajs-stubs" % scalaJSVersion % "provided",
      "org.scalacheck" %% "scalacheck" % "1.14.0" % "test",
      "org.scalactic" %% "scalactic" % "3.0.5",
      "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    ),
    PB.targets in Compile := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),
    PB.protoSources in Compile := Seq(
      file("shared/src/main"),
      file("jvm/src/main"),
    ),
  )
  .jvmSettings()
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scalajs-java-time" % "0.2.5"
  )

lazy val zenoJVM = zeno.jvm
lazy val zenoJS = zeno.js
