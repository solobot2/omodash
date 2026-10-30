package com.openlauncher.app.projection

import com.openlauncher.app.projection.taskhost.AdbIdentity
import com.openlauncher.app.projection.taskhost.AdbPacket
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

class LocalAdbTest {
    @Test fun adbPacketUsesLittleEndianHeaderChecksumAndMagic() {
        val bytes = AdbPacket("WRTE", 3, 1, byteArrayOf(0, -1, 2)).encode()
        assertArrayEquals(byteArrayOf(0x57, 0x52, 0x54, 0x45), bytes.copyOfRange(0, 4))
        assertEquals(257, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(16))
        val decoded = AdbPacket.decode(bytes.copyOfRange(0, 24), bytes.copyOfRange(24, bytes.size))
        assertEquals("WRTE", decoded.command)
        assertEquals(3, decoded.arg0)
        assertArrayEquals(byteArrayOf(0, -1, 2), decoded.data)
    }
    @Test(expected = IllegalStateException::class) fun corruptedPacketIsRejected() {
        val bytes = AdbPacket("CNXN", 0x01000000, 4096, byteArrayOf(1)).encode()
        AdbPacket.decode(bytes.copyOfRange(0, 24), byteArrayOf(2))
    }
    @Test fun persistedIdentityMatchesAndroidPublicKeyAndPrehashedSha1Signature() {
        val file = java.io.File.createTempFile("omodash-adb", ".key").apply { delete() }
        try {
            val identity = AdbIdentity.load(file)
            val public = identity.publicKey()
            assertArrayEquals(public, AdbIdentity.load(file).publicKey())
            val b = ByteBuffer.wrap(public).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(64, b.int)
            val n0inv = b.int.toLong() and 0xffffffffL
            val modulus = ByteArray(256).also(b::get).reversedArray().let { BigInteger(1, it) }
            val rr = ByteArray(256).also(b::get).reversedArray().let { BigInteger(1, it) }
            val exponent = b.int
            assertEquals(65537, exponent)
            assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)
            assertEquals(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE),
                modulus.multiply(BigInteger.valueOf(n0inv)).mod(BigInteger.ONE.shiftLeft(32)))
            val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong())))
            val token = ByteArray(20) { (it + 1).toByte() }
            val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.DECRYPT_MODE, key); doFinal(identity.sign(token))
            }
            assertEquals(35, decrypted.size)
            assertArrayEquals(token, decrypted.copyOfRange(15, 35))
        } finally { file.delete() }
    }
}
