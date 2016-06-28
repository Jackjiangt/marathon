package mesosphere.marathon.integration.setup

import java.nio.file.{ Files, Path }
import java.util.concurrent.Semaphore

import mesosphere.marathon.test.zk.NoRetryPolicy
import mesosphere.util.PortAllocator
import mesosphere.util.state.zk.RichCuratorFramework
import org.apache.commons.io.FileUtils
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.zookeeper.server.{ ServerConfig, ZooKeeperServerMain }
import org.scalatest.{ BeforeAndAfterAll, Suite }

import scala.util.Try

/**
  * Runs ZooKeeper in memory at the given port.
  * The server can be started and stopped at will.
  *
  * close() should be called when the server is no longer necessary (e.g. try-with-resources)
  *
  * @param autoStart Start zookeeper in the background
  * @param port The port to run ZK on
  */
class ZookeeperServer(
    autoStart: Boolean = true,
    val port: Int = PortAllocator.ephemeralPort()) extends AutoCloseable {
  private val workDir: Path = Files.createTempDirectory("zk")
  private val semaphore = new Semaphore(0)
  private val config = {
    val config = new ServerConfig
    config.parse(Array(port.toString, workDir.toFile.getAbsolutePath))
    config
  }
  private val zk = new ZooKeeperServerMain with AutoCloseable {
    def close(): Unit = super.shutdown()
  }
  private val thread = new Thread(new Runnable {
    override def run(): Unit = {
      while (true) {
        zk.runFromConfig(config)
        semaphore.acquire()
      }
    }
  }, s"Zookeeper-$port")
  private var started = autoStart
  if (autoStart) {
    thread.start()
  }

  val connectUri = s"127.0.0.1:$port"

  def start(): Unit = if (!started) {
    if (thread.getState == Thread.State.NEW) {
      thread.start()
    }
    started = true
    semaphore.release()
  }

  def stop(): Unit = if (started) {
    zk.close()
    started = false
  }

  override def close(): Unit = {
    Try(stop())
    Try(FileUtils.deleteDirectory(workDir.toFile))
    thread.interrupt()
    thread.join()
  }
}

object ZookeeperServer {
  def apply(
    autoStart: Boolean = true,
    port: Int = PortAllocator.ephemeralPort()): ZookeeperServer =
    new ZookeeperServer(autoStart, port)
}

trait ZookeeperServerTest extends BeforeAndAfterAll { this: Suite =>
  val zkServer = ZookeeperServer(autoStart = false)

  def zkClient(retryPolicy: RetryPolicy = NoRetryPolicy): RichCuratorFramework = {
    val client = CuratorFrameworkFactory.newClient(zkServer.connectUri, retryPolicy)
    client.start()
    client
  }

  abstract override def beforeAll(): Unit = {
    zkServer.start()
    super.beforeAll()
  }

  abstract override def afterAll(): Unit = {
    zkServer.close()
    super.afterAll()
  }
}