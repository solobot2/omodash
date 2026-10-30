package com.openlauncher.app.projection

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlauncher.app.projection.taskhost.AdbPacket
import com.openlauncher.app.projection.taskhost.LocalAdb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Independent app-owned loopback server; does not change the emulator's adbd. */
@RunWith(AndroidJUnit4::class)
class LocalAdbAuthIntegrationTest {
    @Test fun rsaAuthorizationPublicKeyAndReconnectWorkThroughRealSocket() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            server.soTimeout = 10_000
            val result = executor.submit {
                var publicKey: PublicKey? = null
                repeat(2) { round -> server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    fun read(): AdbPacket {
                        val header = ByteArray(24).also(input::readFully)
                        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
                        return AdbPacket.decode(header, ByteArray(size).also(input::readFully))
                    }
                    fun send(command: String, a: Int, b: Int, body: ByteArray = byteArrayOf()) {
                        val bytes = AdbPacket(command, a, b, body).encode()
                        socket.getOutputStream().write(bytes, 0, 1)
                        socket.getOutputStream().write(bytes, 1, bytes.size - 1)
                        socket.getOutputStream().flush()
                    }
                    assertEquals("CNXN", read().command)
                    val message = "ADB fixture $round".toByteArray()
                    val challenge = MessageDigest.getInstance("SHA-1").digest(message)
                    send("AUTH", 1, 0, challenge)
                    val signature = read()
                    assertEquals("AUTH", signature.command)
                    assertEquals(2, signature.arg0)
                    if (publicKey == null) {
                        send("AUTH", 1, 0, challenge)
                        val public = read()
                        assertEquals(3, public.arg0)
                        val text = public.data.toString(Charsets.US_ASCII)
                        assertTrue(text.endsWith(" omodash@localhost\u0000"))
                        val encoded = Base64.decode(text.substringBefore(' '), Base64.NO_WRAP)
                        val key = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(64, key.int)
                        key.int
                        val modulus = BigInteger(1, ByteArray(256).also(key::get).reversedArray())
                        key.position(520)
                        publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(key.int.toLong())))
                    }
                    assertTrue(Signature.getInstance("SHA1withRSA").run { initVerify(publicKey); update(message); verify(signature.data) })
                    send("CNXN", 0x01000000, 4096, "device::fixture\u0000".toByteArray())
                    val open = read()
                    assertEquals("OPEN", open.command)
                    assertEquals("shell:echo fixture\u0000", open.data.toString(Charsets.UTF_8))
                    send("OKAY", 7, 1)
                    send("WRTE", 7, 1, "ready".toByteArray())
                    assertEquals("OKAY", read().command)
                    send("CLSE", 7, 1)
                    assertEquals("CLSE", read().command)
                } }
            }
            try {
                repeat(2) {
                    val output = StringBuilder()
                    LocalAdb(InstrumentationRegistry.getInstrumentation().targetContext, server.localPort).use {
                        it.run("echo fixture", { true }) { part -> output.append(part) }
                    }
                    assertEquals("ready", output.toString())
                }
                result.get(15, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
