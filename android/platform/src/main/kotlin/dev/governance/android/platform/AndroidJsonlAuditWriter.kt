package dev.governance.android.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.governance.audit.AuditEntry
import dev.governance.audit.AuditReader
import dev.governance.attestation.AttestationVerifier
import dev.governance.attestation.CanonicalJson
import dev.governance.core.AuditRecord
import dev.governance.core.AuditWriter
import dev.governance.core.SystemEventRecord
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [AuditWriter] that persists JSONL records to encrypted append-only files
 * under `context.filesDir/audit/`.
 *
 * ## Encryption
 *
 * Each JSONL line is independently encrypted with AES-256-GCM using a
 * Keystore-protected symmetric key (separate from the Ed25519 signing key).
 * On-disk format: one Base64-encoded `nonce||ciphertext` per line. This
 * preserves line-oriented append semantics while encrypting at rest.
 *
 * ## Rotation
 *
 * Files rotate at [maxFileSizeBytes] (default 10 MB). Rotated files are
 * named `audit_NNNN.jsonl.enc` with an incrementing counter.
 *
 * ## Durability
 *
 * Every write fsyncs the file descriptor to survive process death.
 *
 * ## Reading
 *
 * [readAll] decrypts all rotated files and returns [AuditRecord] objects
 * compatible with the existing [AuditReader]. [verifyIntegrity] walks all
 * records and re-checks every attestation signature.
 */
class AndroidJsonlAuditWriter(
    private val auditDir: File,
    private val maxFileSizeBytes: Long = 10 * 1024 * 1024, // 10 MB
) : AuditWriter {

    /**
     * Convenience constructor from Android [Context].
     */
    constructor(context: Context) : this(File(context.filesDir, "audit"))

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!auditDir.exists()) auditDir.mkdirs()
        ensureEncryptionKey()
    }

    private fun ensureEncryptionKey() {
        if (!keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            val keygen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            keygen.init(spec)
            keygen.generateKey()
        }
    }

    private fun getEncryptionKey(): SecretKey {
        val entry = keyStore.getEntry(ENCRYPTION_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Audit encryption key not found in Keystore")
        return entry.secretKey
    }

    private fun encryptLine(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())
        val nonce = cipher.iv // GCM generates a random IV
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = nonce + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decryptLine(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        val nonce = combined.copyOfRange(0, GCM_NONCE_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_NONCE_LENGTH, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey(), spec)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    override fun write(record: AuditRecord) {
        writeLine(CanonicalJson.encode(AuditRecord.serializer(), record))
    }

    override fun writeSystemEvent(event: SystemEventRecord) {
        writeLine(CanonicalJson.encode(SystemEventRecord.serializer(), event))
    }

    private fun writeLine(json: String) {
        val encrypted = encryptLine(json)
        val file = currentFile()
        FileOutputStream(file, true).use { fos ->
            fos.write((encrypted + "\n").toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (file.length() > maxFileSizeBytes) {
            rotateFile(file)
        }
    }

    private fun currentFile(): File {
        val active = File(auditDir, ACTIVE_FILE_NAME)
        if (!active.exists()) active.createNewFile()
        return active
    }

    private fun rotateFile(file: File) {
        val counter = auditDir.listFiles()
            ?.filter { it.name.startsWith("audit_") && it.name.endsWith(".jsonl.enc") }
            ?.size ?: 0
        val rotatedName = "audit_%04d.jsonl.enc".format(counter)
        file.renameTo(File(auditDir, rotatedName))
    }

    /**
     * Read all audit records from all rotated and active files, decrypting
     * each line and parsing via [AuditReader].
     */
    fun readAll(): List<AuditRecord> {
        val files = allFiles()
        val allPlaintext = buildString {
            for (file in files) {
                file.readLines(Charsets.UTF_8)
                    .filter { it.isNotBlank() }
                    .forEach { encryptedLine ->
                        appendLine(decryptLine(encryptedLine))
                    }
            }
        }
        if (allPlaintext.isBlank()) return emptyList()
        return AuditReader.readAll(allPlaintext.reader())
    }

    /**
     * Verify integrity of all stored audit records. Returns a list of
     * (index, record) pairs where the attestation signature fails verification.
     */
    fun verifyIntegrity(): List<Pair<Int, AuditRecord>> {
        val records = readAll()
        val integrityFailures = AuditReader.verifyIntegrity(records).toMutableList()

        // Also verify Ed25519 signatures on each record
        records.forEachIndexed { index, record ->
            if (!AttestationVerifier.verify(record.decision)) {
                integrityFailures.add(index to record)
            }
        }

        return integrityFailures.distinctBy { it.first }
    }

    private fun allFiles(): List<File> {
        val rotated = auditDir.listFiles()
            ?.filter { it.name.startsWith("audit_") && it.name.endsWith(".jsonl.enc") }
            ?.sortedBy { it.name }
            ?: emptyList()
        val active = File(auditDir, ACTIVE_FILE_NAME)
        return if (active.exists()) rotated + active else rotated
    }

    companion object {
        const val ENCRYPTION_KEY_ALIAS = "governance_audit_aes_key"
        const val ACTIVE_FILE_NAME = "audit_active.jsonl.enc"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
