/*
 *
 *  * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 *
 */
package play.core.server.ssl

import org.specs2.mutable.{ After, Specification }
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.core.ApplicationProvider
import scala.util.Failure
import java.io.File
import javax.net.ssl.SSLEngine
import play.server.api.SSLEngineProvider

class WrongSSLEngineProvider {}

class RightSSLEngineProvider(appPro: ApplicationProvider) extends SSLEngineProvider with Mockito {
  override def createSSLEngine: SSLEngine = {
    require(appPro != null)
    mock[SSLEngine]
  }
}

class JavaSSLEngineProvider(appPro: play.server.ApplicationProvider) extends play.server.SSLEngineProvider with Mockito {
  override def createSSLEngine: SSLEngine = {
    require(appPro != null)
    mock[SSLEngine]
  }
}

class ServerSSLEngineSpec extends Specification with Mockito {

  sequential

  trait ApplicationContext extends Mockito with Scope {
    val applicationProvider = mock[ApplicationProvider]
    applicationProvider.get returns Failure(new Exception("no app"))
  }

  trait TempConfDir extends After {
    val tempDir = File.createTempFile("ServerSSLEngine", ".tmp")
    tempDir.delete()
    val confDir = new File(tempDir, "conf")
    confDir.mkdirs()

    def after = {
      confDir.listFiles().foreach(f => f.delete())
      tempDir.listFiles().foreach(f => f.delete())
      tempDir.delete()
    }
  }

  val javaAppProvider = mock[play.core.ApplicationProvider]

  "ServerSSLContext" should {

    "default create a SSL engine suitable for development" in new ApplicationContext with TempConfDir {
      applicationProvider.path returns tempDir
      System.clearProperty("play.http.sslengineprovider")
      ServerSSLEngine.createSSLEngineProvider(applicationProvider).createSSLEngine() should beAnInstanceOf[SSLEngine]
    }

    "fail to load a non existing SSLEngineProvider" in new ApplicationContext {
      System.setProperty("play.http.sslengineprovider", "bla bla")
      ServerSSLEngine.createSSLEngineProvider(applicationProvider) should throwA[ClassNotFoundException]
    }

    "fail to load an existing SSLEngineProvider with the wrong type" in new ApplicationContext {
      System.setProperty("play.http.sslengineprovider", classOf[WrongSSLEngineProvider].getName)
      ServerSSLEngine.createSSLEngineProvider(applicationProvider) should throwA[ClassCastException]
    }

    "load a custom SSLContext from a SSLEngineProvider" in new ApplicationContext {
      System.setProperty("play.http.sslengineprovider", classOf[RightSSLEngineProvider].getName)
      ServerSSLEngine.createSSLEngineProvider(applicationProvider).createSSLEngine() should beAnInstanceOf[SSLEngine]
    }

    "load a custom SSLContext from a java SSLEngineProvider" in new ApplicationContext {
      System.setProperty("play.http.sslengineprovider", classOf[JavaSSLEngineProvider].getName)
      ServerSSLEngine.createSSLEngineProvider(applicationProvider).createSSLEngine() should beAnInstanceOf[SSLEngine]
    }
  }

}