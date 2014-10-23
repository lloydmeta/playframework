/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play

import sbt._
import sbt.Keys._
import play.PlayImport._
import PlayKeys._

import com.typesafe.sbt.web.SbtWeb.autoImport._

import play.sbtplugin.Colors

import Keys._
import java.lang.{ ProcessBuilder => JProcessBuilder }
import sbt.complete.Parsers._

import scala.util.control.NonFatal
import sbt.inc.{ Analysis, Stamp, Exists, Hash, LastModified }
import sbt.compiler.AggressiveCompile
import play.sbtplugin.test.PlayTestListener

trait PlayCommands extends PlayEclipse with PlayInternalKeys {
  this: PlayReloader =>

  //- mainly scala, mainly java or none

  val JAVA = "java"
  val SCALA = "scala"
  val NONE = "none"

  //- test reporter
  protected lazy val testListener = new PlayTestListener

  val testResultReporter = TaskKey[List[String]]("test-result-reporter")
  val testResultReporterTask = (state, thisProjectRef) map { (s, r) =>
    testListener.result.toList
  }
  val testResultReporterReset = TaskKey[Unit]("test-result-reporter-reset")
  val testResultReporterResetTask = (state, thisProjectRef) map { (s, r) =>
    testListener.result.clear
  }

  val playReloadTask = Def.task(playCompileEverything.value.reduceLeft(_ ++ _))

  def intellijCommandSettings = {
    import org.sbtidea.SbtIdeaPlugin

    // This stuff is all private in the IDEA plugin, so let's copy it here
    val WithSources = "with-sources=yes"
    val NoSources = "no-sources"
    val NoClassifiers = "no-classifiers"
    val SbtClassifiers = "sbt-classifiers"
    val NoFsc = "no-fsc"
    val NoTypeHighlighting = "no-type-highlighting"
    val NoSbtBuildModule = "no-sbt-build-module"

    val args = (Space ~> NoClassifiers | Space ~> SbtClassifiers | Space ~> NoFsc | Space ~> NoTypeHighlighting | Space ~> NoSbtBuildModule | Space ~> WithSources | Space ~> NoSources).*

    SbtIdeaPlugin.settings ++ Seq(
      commands += Command("idea")(_ => args) { (state, args) =>
        // Firstly, attempt to compile the project, but ignore the result
        Project.runTask(compile in Compile, state)

        SbtIdeaPlugin.doCommand(state, if (!args.contains(WithSources) && !(args.contains(NoSources) || args.contains(NoClassifiers))) {
          args :+ NoClassifiers
        } else {
          args
        })
      }
    )
  }

  def configuredEbeanModels = Def.setting {
    import com.typesafe.config._
    import collection.JavaConverters._

    val config = sys.props.get("config.resource").map(ConfigFactory.parseResources).getOrElse {
      val defaultFile = baseDirectory.value / "conf" / "application.conf"
      val configFile = sys.props.get("config.file").fold(defaultFile)(file)
      ConfigFactory.parseFileAnySyntax(configFile)
    }

    try {
      config.getConfig("ebean").entrySet.asScala.map(_.getValue.unwrapped).toSet.mkString(",")
    } catch {
      case e: ConfigException.Missing => "models.*"
    }
  }

  // ----- Post compile (need to be refactored and fully configurable)

  def PostCompile(scope: Configuration) = (sourceDirectory in scope, dependencyClasspath in scope, compile in scope, javaSource in scope, managedSourceDirectories in scope, classDirectory in scope, compileInputs in compile in scope, ebeanModels in scope) map { (src, deps, analysis, javaSrc, srcManaged, classes, inputs, models) =>

    val classpath = (deps.map(_.data.getAbsolutePath).toArray :+ classes.getAbsolutePath).mkString(java.io.File.pathSeparator)
    val ebeanEnhancement = classpath.contains("play-java-ebean")

    // EBean
    if (ebeanEnhancement) {

      val originalContextClassLoader = Thread.currentThread.getContextClassLoader

      try {

        val cp = deps.map(_.data.toURI.toURL).toArray :+ classes.toURI.toURL

        Thread.currentThread.setContextClassLoader(new java.net.URLClassLoader(cp, ClassLoader.getSystemClassLoader))

        import com.avaje.ebean.enhance.agent._
        import com.avaje.ebean.enhance.ant._

        val cl = ClassLoader.getSystemClassLoader

        val t = new Transformer(cp, "debug=-1")

        val ft = new OfflineFileTransform(t, cl, classes.getAbsolutePath, classes.getAbsolutePath)

        try {
          ft.process(models)
        } catch {
          case NonFatal(_) =>
        }

      } finally {
        Thread.currentThread.setContextClassLoader(originalContextClassLoader)
      }
    }

    // Copy managed classes - only needed in Compile scope
    // This is done to ease integration with Eclipse, but it's doubtful as to how effective it is.
    if (scope.name.toLowerCase == "compile") {
      val managedClassesDirectory = classes.getParentFile / (classes.getName + "_managed")

      val managedClasses = ((srcManaged ** "*.scala").get ++ (srcManaged ** "*.java").get).map { managedSourceFile =>
        analysis.relations.products(managedSourceFile)
      }.flatten pair rebase(classes, managedClassesDirectory)

      // Copy modified class files
      val managedSet = IO.copy(managedClasses)

      // Remove deleted class files
      (managedClassesDirectory ** "*.class").get.filterNot(managedSet.contains(_)).foreach(_.delete())
    }

    if (ebeanEnhancement) {
      val javaClasses = (javaSrc ** "*.java").get flatMap { sourceFile =>
        analysis.relations.products(sourceFile)
      }

      /**
       * Updates stamp of product (class file) by preserving the type of a passed stamp.
       * This way any stamp incremental compiler chooses to use to mark class files will
       * be supported.
       */
      def updateStampForClassFile(classFile: File, stamp: Stamp): Stamp = stamp match {
        case _: Exists => Stamp.exists(classFile)
        case _: LastModified => Stamp.lastModified(classFile)
        case _: Hash => Stamp.hash(classFile)
      }
      // Since we may have modified some of the products of the incremental compiler, that is, the compiled template
      // classes and compiled Java sources, we need to update their timestamps in the incremental compiler, otherwise
      // the incremental compiler will see that they've changed since it last compiled them, and recompile them.
      val updatedAnalysis = analysis.copy(stamps = javaClasses.foldLeft(analysis.stamps) { (stamps, classFile) =>
        val existingStamp = stamps.product(classFile)
        if (existingStamp == Stamp.notPresent) {
          throw new java.io.IOException("Tried to update a stamp for class file that is not recorded as "
            + s"product of incremental compiler: $classFile")
        }
        stamps.markProduct(classFile, updateStampForClassFile(classFile, existingStamp))
      })

      // Need to persist the updated analysis.
      val agg = new AggressiveCompile(inputs.incSetup.cacheFile)
      // Load the old one. We do this so that we can get a copy of CompileSetup, which is the cache compiler
      // configuration used to determine when everything should be invalidated. We could calculate it ourselves, but
      // that would by a heck of a lot of fragile code due to the vast number of things we would have to depend on.
      // Reading it out of the existing file is good enough.
      val existing: Option[(Analysis, CompileSetup)] = agg.store.get()
      // Since we've just done a compile before this task, this should never return None, so don't worry about what to
      // do when it returns None.
      existing.foreach {
        case (_, compileSetup) => agg.store.set(updatedAnalysis, compileSetup)
      }

      updatedAnalysis
    } else {
      analysis
    }
  }

  // ----- Play prompt

  val playPrompt = { state: State =>

    val extracted = Project.extract(state)
    import extracted._

    (name in currentRef get structure.data).map { name =>
      "[" + Colors.cyan(name) + "] $ "
    }.getOrElse("> ")

  }

  // ----- Play commands

  private def fork(args: Seq[String]) = {
    val builder = new JProcessBuilder(args: _*)
    Process(builder).run(JvmIO(new JvmLogger(), false))
  }

  val shCommand = Command.args("sh", "<shell command>") { (state: State, args: Seq[String]) =>
    if (args.isEmpty)
      println("sh <command to run>")
    else
      fork(args)
    state
  }

  // -- Utility methods for 0.10-> 0.11 migration
  def inAllDeps[T](base: ProjectRef, deps: ProjectRef => Seq[ProjectRef], key: SettingKey[T], data: Settings[Scope]): Seq[T] =
    inAllProjects(Dag.topologicalSort(base)(deps), key, data)
  def inAllProjects[T](allProjects: Seq[Reference], key: SettingKey[T], data: Settings[Scope]): Seq[T] =
    allProjects.flatMap { p => key in p get data }

  def inAllDependencies[T](base: ProjectRef, key: SettingKey[T], structure: BuildStructure): Seq[T] = {
    def deps(ref: ProjectRef): Seq[ProjectRef] =
      Project.getProject(ref, structure).toList.flatMap { p =>
        p.dependencies.map(_.project) ++ p.aggregate
      }
    inAllDeps(base, deps, key, structure.data)
  }

  def taskToSetting[T](task: TaskKey[T]): SettingKey[Task[T]] = Scoped.scopedSetting(task.scope, task.key)

  private[this] var commonClassLoader: ClassLoader = _

  val playCommonClassloaderTask = (dependencyClasspath in Compile, streams) map { (classpath, streams) =>
    lazy val commonJars: PartialFunction[java.io.File, java.net.URL] = {
      case jar if jar.getName.startsWith("h2-") || jar.getName == "h2.jar" => jar.toURI.toURL
    }

    if (commonClassLoader == null) {

      // The parent of the system classloader *should* be the extension classloader:
      // http://www.onjava.com/pub/a/onjava/2005/01/26/classloading.html
      // We use this because this is where things like Nashorn are located. We don't use the system classloader
      // because it will be polluted with the sbt launcher and dependencies of the sbt launcher.
      // See https://github.com/playframework/playframework/issues/3420 for discussion.
      val parent = ClassLoader.getSystemClassLoader.getParent
      streams.log.debug("Using parent loader for play common classloader: " + parent)

      commonClassLoader = new java.net.URLClassLoader(classpath.map(_.data).collect(commonJars).toArray, parent) {
        override def toString = "Common ClassLoader: " + getURLs.map(_.toString).mkString(",")
      }
    }

    commonClassLoader
  }

  val playCompileEverythingTask = (state, thisProjectRef) flatMap { (s, r) =>
    inAllDependencies(r, taskToSetting(playAssetsWithCompilation), Project structure s).join
  }

  val h2Command = Command.command("h2-browser") { state: State =>
    try {
      val commonLoader = Project.runTask(playCommonClassloader, state).get._2.toEither.right.get
      val h2ServerClass = commonLoader.loadClass(classOf[org.h2.tools.Server].getName)
      h2ServerClass.getMethod("main", classOf[Array[String]]).invoke(null, Array.empty[String])
    } catch {
      case e: Exception => e.printStackTrace
    }
    state
  }

  val licenseCommand = Command.command("license") { state: State =>
    println(
      """
      |This software is licensed under the Apache 2 license, quoted below.
      |
      |Copyright 2013 Typesafe <http://www.typesafe.com>
      |
      |Licensed under the Apache License, Version 2.0 (the "License"); you may not
      |use this file except in compliance with the License. You may obtain a copy of
      |the License at
      |
      |    http://www.apache.org/licenses/LICENSE-2.0
      |
      |Unless required by applicable law or agreed to in writing, software
      |distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
      |WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
      |License for the specific language governing permissions and limitations under
      |the License.
      """.stripMargin)
    state
  }

  val classpathCommand = Command.command("classpath") { state: State =>

    val extracted = Project.extract(state)

    Project.runTask(dependencyClasspath in Runtime, state).get._2.toEither match {
      case Left(_) => {
        println()
        println("Cannot compute the classpath")
        println()
        state.fail
      }
      case Right(classpath) => {
        println()
        println("Here is the computed classpath of your application:")
        println()
        classpath.foreach { item =>
          println("\t- " + item.data.getAbsolutePath)
        }
        println()
        state
      }
    }

  }

  val playMonitoredFilesTask = (thisProjectRef, state) map { (ref, state) =>
    val src = inAllDependencies(ref, unmanagedSourceDirectories in Compile, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    val resources = inAllDependencies(ref, unmanagedResourceDirectories in Compile, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    val assets = inAllDependencies(ref, unmanagedSourceDirectories in Assets, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    val public = inAllDependencies(ref, unmanagedResourceDirectories in Assets, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    (src ++ resources ++ assets ++ public).map { f =>
      if (!f.exists) f.mkdirs(); f
    }.map(_.getCanonicalPath).distinct
  }

  val computeDependencies = TaskKey[Seq[Map[Symbol, Any]]]("ivy-dependencies")
  val computeDependenciesTask = (deliverLocal, ivySbt, streams, organizationName, moduleName, version, scalaBinaryVersion, crossPaths) map { (_, ivySbt, s, org, id, version, scalaVersion, crossPathsValue) =>

    import scala.xml._

    ivySbt.withIvy(s.log) { ivy =>
      val file = crossPathsValue match {
        case false => ivy.getResolutionCacheManager.getConfigurationResolveReportInCache(org + "-" + id, "runtime")
        case _ => ivy.getResolutionCacheManager.getConfigurationResolveReportInCache(org + "-" + id + "_" + scalaVersion, "runtime")
      }
      val report = XML.loadFile(file)

      val deps: Seq[Map[Symbol, Any]] = (report \ "dependencies" \ "module").flatMap { module =>

        (module \ "revision").map { rev =>
          Map(
            'module -> (((module \ "@organisation").text, (module \ "@name").text, rev \ "@name")),
            'evictedBy -> (rev \ "evicted-by").headOption.map(e => (e \ "@rev").text),
            'requiredBy -> (rev \ "caller").map { caller =>
              ((caller \ "@organisation").text, (caller \ "@name").text, (caller \ "@callerrev").text)
            },
            'artifacts -> (rev \ "artifacts" \ "artifact").flatMap { artifact =>
              (artifact \ "@location").headOption.map(node => new java.io.File(node.text).getName)
            })
        }

      }

      deps.filterNot(_('artifacts).asInstanceOf[Seq[_]].isEmpty)

    }

  }

  val computeDependenciesCommand = Command.command("dependencies") { state: State =>

    val extracted = Project.extract(state)

    Project.runTask(computeDependencies, state).get._2.toEither match {
      case Left(_) => {
        println()
        println("Cannot compute dependencies")
        println()
        state.fail
      }

      case Right(dependencies) => {
        println()
        println("Here are the resolved dependencies of your application:")
        println()

        def asTableRow(module: Map[Symbol, Any]): Seq[(String, String, String, Boolean)] = {
          val formatted = (Seq(module.get('module).map {
            case (org, name, rev) => org + ":" + name + ":" + rev
          }).flatten,

            module.get('requiredBy).collect {
              case callers: Seq[_] => callers.collect {
                case (org, name, rev) => org.toString + ":" + name.toString + ":" + rev.toString
              }
            }.toSeq.flatten,

            module.get('evictedBy).map {
              case Some(rev) => Seq("Evicted by " + rev)
              case None => module.get('artifacts).collect {
                case artifacts: Seq[_] => artifacts.map("As " + _.toString)
              }.toSeq.flatten
            }.toSeq.flatten)
          val maxLines = Seq(formatted._1.size, formatted._2.size, formatted._3.size).max

          formatted._1.padTo(maxLines, "").zip(
            formatted._2.padTo(maxLines, "")).zip(
              formatted._3.padTo(maxLines, "")).map {
                case ((name, callers), notes) => (name, callers, notes, module.get('evictedBy).map { case Some(_) => true; case _ => false }.get)
              }
        }

        def display(modules: Seq[Seq[(String, String, String, Boolean)]]) {
          val c1Size = modules.flatten.map(_._1.size).max
          val c2Size = modules.flatten.map(_._2.size).max
          val c3Size = modules.flatten.map(_._3.size).max

          def bar(length: Int) = (1 to length).map(_ => "-").mkString

          val indent = if (Colors.isANSISupported) 9 else 0
          val lineFormat = "| %-" + (c1Size + indent) + "s | %-" + (c2Size + indent) + "s | %-" + (c3Size + indent) + "s |"
          val separator = "+-%s-+-%s-+-%s-+".format(
            bar(c1Size), bar(c2Size), bar(c3Size))

          println(separator)
          println(lineFormat.format(Colors.cyan("Module"), Colors.cyan("Required by"), Colors.cyan("Note")))
          println(separator)

          modules.foreach { lines =>
            lines.foreach {
              case (module, caller, note, evicted) => {
                println(lineFormat.format(
                  if (evicted) Colors.red(module) else Colors.green(module),
                  Colors.white(caller),
                  if (evicted) Colors.red(note) else Colors.white(note)))
              }
            }
            println(separator)
          }
        }

        display(dependencies.map(asTableRow))

        println()
        state
      }
    }

  }

}
