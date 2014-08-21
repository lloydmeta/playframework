/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api

import javax.inject.Inject

import com.google.inject.Singleton
import play.api.inject.{ NewInstanceInjector, Injector, DefaultApplicationLifecycle }
import play.core._
import play.utils._

import play.api.mvc._

import java.io._

import annotation.implicitNotFound

import reflect.ClassTag
import scala.util.control.NonFatal
import scala.concurrent.{ Future, ExecutionException }

/**
 * A Play application.
 *
 * Application creation is handled by the framework engine.
 *
 * If you need to create an ad-hoc application,
 * for example in case of unit testing, you can easily achieve this using:
 * {{{
 * val application = new DefaultApplication(new File("."), this.getClass.getClassloader, None, Play.Mode.Dev)
 * }}}
 *
 * This will create an application using the current classloader.
 *
 */
@implicitNotFound(msg = "You do not have an implicit Application in scope. If you want to bring the current running Application into context, just add import play.api.Play.current")
trait Application {

  /**
   * The absolute path hosting this application, mainly used by the `getFile(path)` helper method
   */
  def path: File

  /**
   * The application's classloader
   */
  def classloader: ClassLoader

  /**
   * The `SourceMapper` used to retrieve source code displayed in error pages
   */
  def sources: Option[SourceMapper]

  /**
   * `Dev`, `Prod` or `Test`
   */
  def mode: Mode.Mode

  def global: GlobalSettings
  def configuration: Configuration
  def plugins: Seq[Plugin]

  /**
   * Retrieves a plugin of type `T`.
   *
   * For example, retrieving the DBPlugin instance:
   * {{{
   * val dbPlugin = application.plugin(classOf[DBPlugin])
   * }}}
   *
   * @tparam T the plugin type
   * @param  pluginClass the plugin’s class
   * @return the plugin instance, wrapped in an option, used by this application
   * @throws Error if no plugins of type `T` are loaded by this application
   */
  def plugin[T](pluginClass: Class[T]): Option[T] =
    plugins.find(p => pluginClass.isAssignableFrom(p.getClass)).map(_.asInstanceOf[T])

  /**
   * Retrieves a plugin of type `T`.
   *
   * For example, to retrieve the DBPlugin instance:
   * {{{
   * val dbPlugin = application.plugin[DBPlugin].map(_.api).getOrElse(sys.error("problem with the plugin"))
   * }}}
   *
   * @tparam T the plugin type
   * @return The plugin instance used by this application.
   * @throws Error if no plugins of type T are loaded by this application.
   */
  def plugin[T](implicit ct: ClassTag[T]): Option[T] = plugin(ct.runtimeClass).asInstanceOf[Option[T]]

  /**
   * The router used by this application.
   */
  def routes: Router.Routes

  /**
   * Handle a runtime error during the execution of an action
   */
  private[play] def handleError(request: RequestHeader, e: Throwable): Future[Result] = try {
    e match {
      case e: UsefulException => throw e
      case e: ExecutionException => handleError(request, e.getCause)
      case e: Throwable => {

        val source = sources.flatMap(_.sourceFor(e))

        throw new PlayException.ExceptionSource(
          "Execution exception",
          "[%s: %s]".format(e.getClass.getSimpleName, e.getMessage),
          e) {
          def line = source.flatMap(_._2).map(_.asInstanceOf[java.lang.Integer]).orNull
          def position = null
          def input = source.map(_._1).map(PlayIO.readFileAsString).orNull
          def sourceName = source.map(_._1.getAbsolutePath).orNull
        }
      }
    }
  } catch {
    case NonFatal(e) => try {
      Logger.error(
        """
        |
        |! %sInternal server error, for (%s) [%s] ->
        |""".stripMargin.format(e match {
          case p: PlayException => "@" + p.id + " - "
          case _ => ""
        }, request.method, request.uri),
        e
      )
      global.onError(request, e)
    } catch {
      case NonFatal(e) => DefaultGlobal.onError(request, e)
    }
  }

  /**
   * Retrieves a file relative to the application root path.
   *
   * Note that it is up to you to manage the files in the application root path in production.  By default, there will
   * be nothing available in the application root path.
   *
   * For example, to retrieve some deployment specific data file:
   * {{{
   * val myDataFile = application.getFile("data/data.xml")
   * }}}
   *
   * @param relativePath relative path of the file to fetch
   * @return a file instance; it is not guaranteed that the file exists
   */
  def getFile(relativePath: String): File = new File(path, relativePath)

  /**
   * Retrieves a file relative to the application root path.
   * This method returns an Option[File], using None if the file was not found.
   *
   * Note that it is up to you to manage the files in the application root path in production.  By default, there will
   * be nothing available in the application root path.
   *
   * For example, to retrieve some deployment specific data file:
   * {{{
   * val myDataFile = application.getExistingFile("data/data.xml")
   * }}}
   *
   * @param relativePath the relative path of the file to fetch
   * @return an existing file
   */
  def getExistingFile(relativePath: String): Option[File] = Option(getFile(relativePath)).filter(_.exists)

  /**
   * Scans the application classloader to retrieve a resource.
   *
   * The conf directory is included on the classpath, so this may be used to look up resources, relative to the conf
   * directory.
   *
   * For example, to retrieve the conf/logger.xml configuration file:
   * {{{
   * val maybeConf = application.resource("logger.xml")
   * }}}
   *
   * @param name the absolute name of the resource (from the classpath root)
   * @return the resource URL, if found
   */
  def resource(name: String): Option[java.net.URL] = {
    Option(classloader.getResource(Option(name).map {
      case s if s.startsWith("/") => s.drop(1)
      case s => s
    }.get))
  }

  /**
   * Scans the application classloader to retrieve a resource’s contents as a stream.
   *
   * The conf directory is included on the classpath, so this may be used to look up resources, relative to the conf
   * directory.
   *
   * For example, to retrieve the conf/logger.xml configuration file:
   * {{{
   * val maybeConf = application.resourceAsStream("logger.xml")
   * }}}
   *
   * @param name the absolute name of the resource (from the classpath root)
   * @return a stream, if found
   */
  def resourceAsStream(name: String): Option[InputStream] = {
    Option(classloader.getResourceAsStream(Option(name).map {
      case s if s.startsWith("/") => s.drop(1)
      case s => s
    }.get))
  }

  /**
   * Stop the application.  The returned future will be redeemed when all stop hooks have been run.
   */
  def stop(): Future[Unit]

  /**
   * Get the injector for this application.
   *
   * @return The injector.
   */
  def injector: Injector = NewInstanceInjector
}

class OptionalSourceMapper(val sourceMapper: Option[SourceMapper])

@Singleton
class DefaultApplication @Inject() (environment: Environment,
    sourceMapper: OptionalSourceMapper,
    applicationLifecycle: DefaultApplicationLifecycle,
    override val injector: Injector,
    override val configuration: Configuration,
    override val global: GlobalSettings,
    override val routes: Router.Routes,
    override val plugins: Plugins) extends Application {

  def path = environment.rootPath

  def classloader = environment.classLoader

  def mode = environment.mode

  def sources = sourceMapper.sourceMapper

  def stop() = applicationLifecycle.stop()
}

/**
 * Helper to provide the Play built in components.
 */
trait BuiltInComponents {
  def environment: Environment
  def sourceMapper: OptionalSourceMapper
  def webCommands: WebCommands
  def configuration: Configuration
  def global: GlobalSettings

  def routes: Router.Routes

  lazy val applicationLifecycle: DefaultApplicationLifecycle = new DefaultApplicationLifecycle
  lazy val injector: Injector = NewInstanceInjector
  lazy val application: Application = new DefaultApplication(environment, sourceMapper, applicationLifecycle, injector,
    configuration, global, routes, Plugins.empty)
}