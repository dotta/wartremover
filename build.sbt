import ReleaseTransformations._
import com.typesafe.sbt.pgp.PgpKeys._
import com.typesafe.sbt.pgp.PgpSettings.useGpg
import org.wartremover.TravisYaml.travisScalaVersions

lazy val commonSettings = Seq(
  organization := "org.wartremover",
  licenses := Seq(
    "The Apache Software License, Version 2.0" ->
      url("http://www.apache.org/licenses/LICENSE-2.0.txt")
  ),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  scalaVersion := travisScalaVersions.value.last,
  sbtVersion := {
    scalaBinaryVersion.value match {
      case "2.10" => "0.13.17"
      case _      => "1.0.2"
    }
  },
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases"  at nexus + "service/local/staging/deploy/maven2")
  },
  homepage := Some(url("http://wartremover.org")),
  useGpg := true,
  pomExtra :=
    <scm>
      <url>git@github.com:wartremover/wartremover.git</url>
      <connection>scm:git:git@github.com:wartremover/wartremover.git</connection>
    </scm>
    <developers>
      <developer>
        <id>puffnfresh</id>
        <name>Brian McKenna</name>
        <url>http://brianmckenna.org/</url>
      </developer>
      <developer>
        <name>Chris Neveu</name>
        <url>http://chrisneveu.com</url>
      </developer>
    </developers>
)

lazy val root = Project(
  id = "root",
  base = file("."),
  aggregate = Seq(core, sbtPlug)
).settings(commonSettings ++ Seq(
  publishArtifact := false,
  releaseCrossBuild := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    releaseStepCommandAndRemaining("+test"),
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("+publishSigned"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
): _*).enablePlugins(CrossPerProjectPlugin)

lazy val core = Project(
  id = "core",
  base = file("core")
).settings(commonSettings ++ Seq(
  name := "wartremover",
  fork in Test := true,
  crossScalaVersions := travisScalaVersions.value,
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
  libraryDependencies := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) =>
        libraryDependencies.value :+ ("org.scalamacros" %% "quasiquotes" % "2.0.1")
      case _ =>
        libraryDependencies.value
    }
  },
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    "org.scalatest" %% "scalatest" % "3.0.5-M1" % "test"
  ),
  assemblyOutputPath in assembly := file("./wartremover-assembly.jar")
): _*)
  .dependsOn(testMacros % "test->compile")
  .enablePlugins(CrossPerProjectPlugin)
  .enablePlugins(TravisYaml)

lazy val sbtPlug: Project = Project(
  id = "sbt-plugin",
  base = file("sbt-plugin")
).settings(commonSettings ++ Seq(
  name := "sbt-wartremover",
  sbtPlugin := true,
  crossScalaVersions := travisScalaVersions.value.filter(v => Seq("2.12", "2.10").exists(v.startsWith)),
  sourceGenerators in Compile += Def.task {
    val base = (sourceManaged in Compile).value
    val file = base / "wartremover" / "Wart.scala"
    val wartsDir = core.base / "src" / "main" / "scala" / "wartremover" / "warts"
    val warts: Seq[String] = wartsDir.listFiles.toSeq.map(_.getName.replaceAll("""\.scala$""", "")).
      filterNot(Seq("Unsafe", "ForbidInference") contains _).sorted
    val unsafe = warts.filter(IO.read(wartsDir / "Unsafe.scala") contains _)
    val content =
      s"""package wartremover
         |// Autogenerated code, see build.sbt.
         |final class Wart private[wartremover](val clazz: String) {
         |  override def toString: String = clazz
         |}
         |object Wart {
         |  private[wartremover] val PluginVersion = "${version.value}"
         |  private[wartremover] lazy val AllWarts = List(${warts mkString ", "})
         |  private[wartremover] lazy val UnsafeWarts = List(${unsafe mkString ", "})
         |  /** A fully-qualified class name of a custom Wart implementing `org.wartremover.WartTraverser`. */
         |  def custom(clazz: String): Wart = new Wart(clazz)
         |  private[this] def w(nm: String): Wart = new Wart(s"org.wartremover.warts.$$nm")
         |""".stripMargin +
        warts.map(w => s"""  val $w = w("${w}")""").mkString("\n") + "\n}\n"
    IO.write(file, content)
    Seq(file)
  }
): _*)
  .enablePlugins(CrossPerProjectPlugin)
  .enablePlugins(TravisYaml)

lazy val testMacros: Project = Project(
  id = "test-macros",
  base = file("test-macros")
).settings(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "macro-compat" % "1.1.1",
    "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.patch)
  )
).enablePlugins(CrossPerProjectPlugin)
