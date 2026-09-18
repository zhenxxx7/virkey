package com.virkey.app.network

import com.virkey.app.ui.RemoteAction
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Real TLS sockets on loopback only. Never injects input into the test computer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiControllerTest {
    @Test fun inspectionClosesWithoutSendingPinAndLeavesConfirmationPending() {
        val received = AtomicReference<String?>("not-read")
        LocalHost { server ->
            server.acceptClient().use { socket ->
                socket.startHandshake()
                received.set(socket.inputStream.bufferedReader().readLine())
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "123456")
                waitUntil { controller.state.value.pendingFingerprint != null }
                host.assertCompleted()
                assertNull(received.get())
                assertEquals(TestTlsIdentity.fingerprint, controller.state.value.pendingFingerprint)
                assertFalse(controller.state.value.isConnected)
                assertFalse(controller.state.value.isConnecting)
            }
        }
    }

    @Test fun confirmedConnectionSendsOrderedInputAndMediaCommands() {
        val frames = CopyOnWriteArrayList<JSONObject>()
        val received = CountDownLatch(1)
        val finish = CountDownLatch(1)
        LocalHost { server ->
            server.acceptClient().use { socket ->
                val reader = socket.inputStream.bufferedReader()
                val writer = socket.outputStream.bufferedWriter()
                val auth = JSONObject(requireNotNull(reader.readLine()))
                assertEquals("auth", auth.getString("type"))
                assertEquals("012345", auth.getString("pin"))
                writer.frame("""{"type":"ready","name":"Loopback PC","ledsKnown":true,"capsLock":true,"numLock":true}""")
                writer.frame("""{"type":"nowPlaying","available":true,"sessionId":"s","trackId":"t","title":"Test track","canSeek":true,"durationMs":10000}""")
                repeat(4) { frames += reader.applicationFrame(writer) }
                writer.frame("""{"type":"leds","ledsKnown":false,"capsLock":true,"numLock":true}""")
                received.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "012345", TestTlsIdentity.fingerprint)
                waitUntil { controller.state.value.isConnected && controller.state.value.nowPlaying.available }
                assertTrue(controller.state.value.capsLock)
                assertTrue(controller.state.value.numLock)
                assertTrue(controller.state.value.ledsKnown)
                assertEquals("Loopback PC", controller.state.value.hostName)
                controller.send(RemoteAction.KeyDown(4))
                controller.send(RemoteAction.KeyUp(4))
                controller.media("seek", "s", "t", positionMs = 2000)
                // Wait until these ordered events reach the host before clearing any pending queue.
                waitUntil { frames.size == 3 }
                controller.send(RemoteAction.ReleaseAll)
                assertTrue(received.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("key", "key", "media", "release"), frames.map { it.getString("type") })
                assertTrue(frames[0].getBoolean("down"))
                assertFalse(frames[1].getBoolean("down"))
                assertEquals(2000, frames[2].getLong("positionMs"))
                waitUntil { !controller.state.value.ledsKnown }
                controller.disconnect()
                assertFalse(controller.state.value.isConnected)
                assertFalse(controller.state.value.nowPlaying.available)
                finish.countDown()
                host.assertCompleted()
            }
        }
    }

    @Test fun wrongConfirmedCertificateNeverSendsCredentials() {
        val received = AtomicReference<String?>(null)
        LocalHost { server ->
            server.acceptClient().use { socket ->
                try {
                    socket.startHandshake()
                    received.set(socket.inputStream.bufferedReader().readLine())
                } catch (_: IOException) {
                    // TLS handshake rejection is the expected result.
                }
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "123456", "00".repeat(32))
                waitUntil { !controller.state.value.isConnecting }
                host.assertCompleted()
                assertNull(received.get())
                assertFalse(controller.state.value.isConnected)
                assertNull(controller.state.value.pendingFingerprint)
                assertTrue(controller.state.value.status.contains("certificate changed"))
            }
        }
    }

    @Test fun authenticationErrorClearsConnectionAndMedia() {
        LocalHost { server ->
            server.acceptClient().use { socket ->
                assertEquals("auth", JSONObject(socket.inputStream.bufferedReader().readLine()).getString("type"))
                socket.outputStream.bufferedWriter().frame("""{"type":"error","message":"Incorrect PIN"}""")
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "123456", TestTlsIdentity.fingerprint)
                waitUntil { !controller.state.value.isConnecting }
                assertEquals("Incorrect PIN", controller.state.value.status)
                assertFalse(controller.state.value.isConnected)
                assertEquals(NowPlayingState(), controller.state.value.nowPlaying)
                host.assertCompleted()
            }
        }
    }

    @Test fun pongCannotKeepUnauthenticatedConnectionAlive() {
        LocalHost { server ->
            server.acceptClient().use { socket ->
                socket.inputStream.bufferedReader().readLine()
                socket.outputStream.bufferedWriter().frame("""{"type":"pong"}""")
                assertNull(socket.inputStream.bufferedReader().readLine())
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "123456", TestTlsIdentity.fingerprint)
                waitUntil { !controller.state.value.isConnecting }
                assertEquals("Virkey Host did not complete pairing", controller.state.value.status)
                assertFalse(controller.state.value.isConnected)
                host.assertCompleted()
            }
        }
    }

    @Test fun idleConnectionSendsHeartbeatWhileReaderWaits() {
        val heartbeat = CountDownLatch(1)
        val finish = CountDownLatch(1)
        LocalHost { server ->
            server.acceptClient().use { socket ->
                val reader = socket.inputStream.bufferedReader()
                val writer = socket.outputStream.bufferedWriter()
                reader.readLine()
                writer.frame("""{"type":"ready","name":"Loopback PC"}""")
                assertEquals("ping", JSONObject(reader.readLine()).getString("type"))
                writer.frame("""{"type":"pong"}""")
                heartbeat.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "123456", TestTlsIdentity.fingerprint)
                assertTrue("Writer/heartbeat starved while reader blocked", heartbeat.await(5, TimeUnit.SECONDS))
                assertTrue(controller.state.value.isConnected)
                assertFalse(controller.state.value.ledsKnown)
                finish.countDown()
                host.assertCompleted()
            }
        }
    }

    @Test fun reconnectStartsWithEmptyInputQueue() {
        val firstReceived = CountDownLatch(1)
        val dropFirst = CountDownLatch(1)
        val secondReceived = CountDownLatch(1)
        val finish = CountDownLatch(1)
        LocalHost { server ->
            server.acceptClient().use { socket ->
                val reader = socket.inputStream.bufferedReader()
                val writer = socket.outputStream.bufferedWriter()
                reader.readLine()
                writer.frame("""{"type":"ready","name":"First"}""")
                assertTrue(reader.applicationFrame(writer).getBoolean("down"))
                firstReceived.countDown()
                assertTrue(dropFirst.await(5, TimeUnit.SECONDS))
            }
            server.acceptClient().use { socket ->
                val reader = socket.inputStream.bufferedReader()
                val writer = socket.outputStream.bufferedWriter()
                assertEquals("auth", JSONObject(reader.readLine()).getString("type"))
                writer.frame("""{"type":"ready","name":"Second"}""")
                val key = reader.applicationFrame(writer)
                assertEquals("key", key.getString("type"))
                assertEquals(7, key.getInt("usage"))
                assertTrue(key.getBoolean("down"))
                secondReceived.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
            }
        }.use { host ->
            WifiController().use { controller ->
                controller.connect(host.address, "123456", TestTlsIdentity.fingerprint)
                waitUntil({ "First connection: " + controller.state.value }) { controller.state.value.isConnected }
                controller.send(RemoteAction.KeyDown(4))
                assertTrue(firstReceived.await(5, TimeUnit.SECONDS))
                dropFirst.countDown()
                waitUntil({ "First disconnect: " + controller.state.value }) { !controller.state.value.isConnected }
                controller.send(RemoteAction.KeyDown(5))
                controller.send(RemoteAction.KeyUp(5))
                controller.connect(host.address, "123456", TestTlsIdentity.fingerprint)
                waitUntil({ "Second connection: " + controller.state.value }) {
                    controller.state.value.isConnected && controller.state.value.hostName == "Second"
                }
                controller.send(RemoteAction.KeyDown(7))
                assertTrue(secondReceived.await(5, TimeUnit.SECONDS))
                finish.countDown()
                host.assertCompleted()
            }
        }
    }

    private fun waitUntil(description: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for Wi-Fi state. " + description(), predicate())
    }

    private class LocalHost(work: (SSLServerSocket) -> Unit) : AutoCloseable {
        val server = TestTlsIdentity.serverContext().serverSocketFactory
            .createServerSocket(0, 2, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val address = "127.0.0.1:" + server.localPort
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "virkey-loopback-test").apply { isDaemon = true }
        }
        private val completion = executor.submit { work(server) }

        init { server.soTimeout = 6000 }
        fun assertCompleted() { completion.get(7, TimeUnit.SECONDS) }
        override fun close() {
            server.close()
            completion.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun SSLServerSocket.acceptClient() = (accept() as SSLSocket).apply { soTimeout = 6000 }

    private fun BufferedWriter.frame(value: String) {
        write(value)
        newLine()
        flush()
    }

    private fun BufferedReader.applicationFrame(writer: BufferedWriter): JSONObject {
        while (true) {
            val frame = JSONObject(requireNotNull(readLine()) { "Client closed before sending input" })
            if (frame.getString("type") == "ping") writer.frame("""{"type":"pong"}""") else return frame
        }
    }
}
