package com.massspx.remotegamestream

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated, encrypted records for the local host/client connection.
 * A fresh random salt is used for every connection. The host pairing code
 * is a random 60-bit, 12-character Base32 value.
 */
class SecureChannel private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendDirection: Int,
    private val receiveDirection: Int,
    private val key: SecretKeySpec
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L
    private val sendLock = Any()
    private val receiveLock = Any()

    fun send(plain: ByteArray) = synchronized(sendLock) {
        require(plain.size <= MAX_RECORD_SIZE) { "Record too large" }
        check(sendCounter >= 0) { "Send counter exhausted" }
        val nonce = nonce(sendDirection, sendCounter++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(nonce)
        val encrypted = cipher.doFinal(plain)
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
    }

    fun receive(): ByteArray = synchronized(receiveLock) {
        check(receiveCounter >= 0) { "Receive counter exhausted" }
        val size = input.readInt()
        if (size < GCM_TAG_BYTES || size > MAX_RECORD_SIZE + GCM_TAG_BYTES) {
            throw SecurityException("Invalid encrypted record size")
        }
        val encrypted = ByteArray(size)
        input.readFully(encrypted)
        val nonce = nonce(receiveDirection, receiveCounter++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(nonce)
        cipher.doFinal(encrypted)
    }

    companion object {
        private const val MAX_RECORD_SIZE = 8 * 1024 * 1024
        private const val GCM_TAG_BYTES = 16
        private const val PBKDF2_ITERATIONS = 210_000
        private const val HOST_TO_CLIENT = 0x48535431
        private const val CLIENT_TO_HOST = 0x434C4931
        private val MAGIC = byteArrayOf(0x52, 0x47, 0x53, 0x31) // RGS1
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generatePairingCode(): String {
            val bytes = ByteArray(12)
            random.nextBytes(bytes)
            return buildString(12) {
                for (i in 0 until 12) append(ALPHABET[bytes[i].toInt() and 31])
            }
        }

        fun accept(socket: Socket, pairingCode: String): SecureChannel {
            socket.soTimeout = 8_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val salt = ByteArray(16).also(random::nextBytes)
            output.write(MAGIC)
            output.write(salt)
            output.flush()

            val keyMaterial = derive(pairingCode, salt)
            val clientProof = ByteArray(32)
            input.readFully(clientProof)
            val expectedClient = hmac(keyMaterial, "client-auth".toByteArray(Charsets.UTF_8) + salt)
            if (!MessageDigest.isEqual(clientProof, expectedClient)) {
                throw SecurityException("Pairing code rejected")
            }
            output.write(hmac(keyMaterial, "server-auth".toByteArray(Charsets.UTF_8) + salt))
            output.flush()
            socket.soTimeout = 0
            val sessionKey = SecretKeySpec(
                hmac(keyMaterial, "session-key".toByteArray(Charsets.UTF_8) + salt), "AES"
            )
            keyMaterial.fill(0)
            return SecureChannel(input, output, HOST_TO_CLIENT, CLIENT_TO_HOST, sessionKey)
        }

        fun connect(socket: Socket, pairingCode: String): SecureChannel {
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!MessageDigest.isEqual(magic, MAGIC)) throw SecurityException("Unrecognized host")
            val salt = ByteArray(16)
            input.readFully(salt)
            val keyMaterial = derive(pairingCode, salt)
            output.write(hmac(keyMaterial, "client-auth".toByteArray(Charsets.UTF_8) + salt))
            output.flush()
            val serverProof = ByteArray(32)
            input.readFully(serverProof)
            val expectedServer = hmac(keyMaterial, "server-auth".toByteArray(Charsets.UTF_8) + salt)
            if (!MessageDigest.isEqual(serverProof, expectedServer)) {
                throw SecurityException("Host authentication failed")
            }
            socket.soTimeout = 0
            val sessionKey = SecretKeySpec(
                hmac(keyMaterial, "session-key".toByteArray(Charsets.UTF_8) + salt), "AES"
            )
            keyMaterial.fill(0)
            return SecureChannel(input, output, CLIENT_TO_HOST, HOST_TO_CLIENT, sessionKey)
        }

        private fun derive(code: String, salt: ByteArray): ByteArray {
            require(code.length == 12 && code.all { it in ALPHABET }) { "Pairing code must be 12 characters" }
            val spec = PBEKeySpec(code.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun nonce(direction: Int, counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()
    }
}
