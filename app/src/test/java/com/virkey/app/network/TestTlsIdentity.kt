package com.virkey.app.network

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Test-only identity created at runtime; no private key is stored in the repository. */
internal object TestTlsIdentity {
    private val identity: KeyStore by lazy {
        val directory = Files.createTempDirectory("virkey-tls-test")
        val store = directory.resolve("identity.p12")
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "keytool.exe" else "keytool"
        val tool = Path.of(requireNotNull(System.getProperty("java.home")), "bin", executable)
        try {
            val process = ProcessBuilder(tool.toString(), "-genkeypair", "-alias", "host",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=Virkey loopback test", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "test-only-password",
                "-keypass", "test-only-password", "-noprompt")
                .redirectErrorStream(true).start()
            check(process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Timed out generating the local TLS test identity"
            }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
            KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(store).use { load(it, "test-only-password".toCharArray()) }
            }
        } finally {
            Files.deleteIfExists(store)
            Files.deleteIfExists(directory)
        }
    }

    val certificate: X509Certificate get() = identity.getCertificate("host") as X509Certificate
    val fingerprint: String get() = certificateFingerprint(certificate)

    fun serverContext(): SSLContext {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identity, "test-only-password".toCharArray())
        }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, SecureRandom()) }
    }
}
