package com.openlauncher.app.projection.taskhost

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher

/** One shell stream, exclusively to the head unit's loopback adbd. No remote hosts. */
internal class LocalAdb(private val context: Context, private val port: Int = 5555) : AutoCloseable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private val keys by lazy { AdbIdentity.load(File(context.noBackupFilesDir, "projection-adb.key")) }

    fun run(command: String, active: () -> Boolean, output: (String) -> Unit) {
        socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
        socket.soTimeout = 30_000
        input = DataInputStream(socket.getInputStream())
        send("CNXN", 0x01000000, 4096, "host::omodash\u0000".toByteArray())
        var signed = false
        var authAttempts = 0
        while (true) {
            socket.soTimeout = 30_000
            val packet = read()
            if (packet.command == "CNXN") break
            check(packet.command == "AUTH" && packet.arg0 == 1 && packet.data.size == 20) { "Unexpected ADB authentication response" }
            if (!signed) {
                send("AUTH", 2, 0, keys.sign(packet.data))
                signed = true
            } else {
                check(authAttempts++ == 0) { "ADB authorization was rejected. Accept the head unit's USB debugging prompt." }
                send("AUTH", 3, 0, (Base64.encodeToString(keys.publicKey(), Base64.NO_WRAP) + " omodash@localhost\u0000").toByteArray())
            }
        }
        check(active()) { "Projection closed" }
        send("OPEN", 1, 0, ("shell:$command\u0000").toByteArray())
        socket.soTimeout = 1_000
        while (active()) {
            val packet = try { read() } catch (_: SocketTimeoutException) { continue }
            check(packet.arg1 == 1) { "Unexpected ADB stream" }
            when (packet.command) {
                "OKAY" -> Unit
                "WRTE" -> { output(packet.data.toString(Charsets.UTF_8)); send("OKAY", 1, packet.arg0) }
                "CLSE" -> { send("CLSE", 1, packet.arg0); return }
                else -> error("Unexpected ADB stream response")
            }
        }
    }

    private fun send(command: String, arg0: Int, arg1: Int, data: ByteArray = byteArrayOf()) {
        socket.getOutputStream().apply { write(AdbPacket(command, arg0, arg1, data).encode()); flush() }
    }

    private fun read(): AdbPacket {
        val header = ByteArray(24)
        // Timeout only before the packet; finish partial packets without losing framing.
        header[0] = input.readByte()
        socket.soTimeout = 30_000
        try {
            input.readFully(header, 1, 23)
            val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            check(size in 0..(1024 * 1024)) { "Invalid ADB packet size" }
            val body = ByteArray(size).also(input::readFully)
            return AdbPacket.decode(header, body)
        } catch (e: SocketTimeoutException) {
            throw java.io.IOException("Incomplete ADB packet", e)
        } finally { socket.soTimeout = 1_000 }
    }

    override fun close() { runCatching { socket.close() } }
}

data class AdbPacket(val command: String, val arg0: Int, val arg1: Int, val data: ByteArray) {
    fun encode(): ByteArray {
        val cmd = ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
        return ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 255 }).putInt(cmd.inv()).put(data).array()
    }
    companion object {
        fun decode(header: ByteArray, body: ByteArray): AdbPacket {
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val arg0 = b.int; val arg1 = b.int; val size = b.int
            val checksum = b.int; val magic = b.int
            check(magic == cmd.inv() && size == body.size && checksum == body.sumOf { it.toInt() and 255 }) { "Invalid ADB packet" }
            return AdbPacket(header.copyOfRange(0, 4).toString(Charsets.US_ASCII), arg0, arg1, body)
        }
    }
}

class AdbIdentity(private val key: RSAPrivateKey) {
    fun sign(token: ByteArray): ByteArray {
        require(token.size == 20)
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key); doFinal(prefix + token)
        }
    }
    fun publicKey(): ByteArray {
        val n = key.modulus
        val word = BigInteger.ONE.shiftLeft(32)
        fun little(value: BigInteger) = value.toByteArray().reversedArray().copyOf(256)
        return ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n.modInverse(word).negate().mod(word).toLong().toInt())
            .put(little(n)).put(little(BigInteger.ONE.shiftLeft(4096).mod(n)))
            .putInt(RSAKeyGenParameterSpec.F4.toInt()).array()
    }
    companion object {
        @Synchronized fun load(file: File): AdbIdentity {
            if (!file.exists()) {
                val generated = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)) }.generateKeyPair().private.encoded
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeBytes(generated)
                check(temp.renameTo(file)) { "Could not save local ADB identity" }
            }
            return AdbIdentity(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(file.readBytes())) as RSAPrivateKey)
        }
    }
}
