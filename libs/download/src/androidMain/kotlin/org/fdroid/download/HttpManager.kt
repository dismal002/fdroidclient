package org.fdroid.download

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.utils.io.jvm.javaio.toInputStream
import okhttp3.ConnectionSpec.Companion.CLEARTEXT
import okhttp3.ConnectionSpec.Companion.MODERN_TLS
import okhttp3.ConnectionSpec.Companion.RESTRICTED_TLS
import okhttp3.Dns
import okhttp3.internal.tls.OkHostnameVerifier
import org.fdroid.fdroid.DigestInputStream
import java.io.InputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal actual fun getHttpClientEngineFactory(
    customDns: Dns?,
    extraTrustAnchors: List<ByteArray>,
): HttpClientEngineFactory<*> {
    val trustManager = if (extraTrustAnchors.isEmpty()) {
        null
    } else {
        buildTrustManager(extraTrustAnchors)
    }
    return object : HttpClientEngineFactory<OkHttpConfig> {
        private val connectionSpecs = listOf(
            RESTRICTED_TLS, // order matters here, so we put restricted before modern
            MODERN_TLS,
            CLEARTEXT, // needed for swap connections, allowed in fdroidclient:app as well
        )

        override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine = OkHttp.create {
            block()
            config {
                if (proxy.isTor()) { // don't allow DNS requests when using Tor
                    dns(NoDns())
                } else if (customDns != null) {
                    dns(customDns)
                }
                hostnameVerifier { hostname, session ->
                    session?.sessionContext?.sessionTimeout = 10
                    // use default hostname verifier
                    OkHostnameVerifier.verify(hostname, session)
                }
                connectionSpecs(connectionSpecs)
                if (trustManager != null) {
                    val context = SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf(trustManager), null)
                    }
                    sslSocketFactory(context.socketFactory, trustManager)
                }
            }
        }
    }
}

/**
 * Trusts the platform anchors plus the certificates the app ships itself, which
 * is the only way to reach a Let's Encrypt host on TVs where the user cannot
 * install a CA and the system store predates the ISRG root.
 */
private fun buildTrustManager(anchors: List<ByteArray>): X509TrustManager {
    val certificates = CertificateFactory.getInstance("X.509")
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    anchors.forEachIndexed { index, pem ->
        keyStore.setCertificateEntry("anchor-$index", certificates.generateCertificate(pem.inputStream()))
    }
    val bundled = trustManagerFor(keyStore)
    val system = trustManagerFor(null)
    return object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            bundled.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                bundled.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                system.checkServerTrusted(chain, authType)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            bundled.acceptedIssuers + system.acceptedIssuers
    }
}

private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

public suspend fun HttpManager.getInputStream(request: DownloadRequest): InputStream {
    return getChannel(request).toInputStream()
}

/**
 * Gets the [InputStream] for the given [request] as a [DigestInputStream],
 * so you can verify the SHA-256 hash.
 * If you don't need to verify the hash, use [getInputStream] instead.
 */
public suspend fun HttpManager.getDigestInputStream(request: DownloadRequest): DigestInputStream {
    val digest = MessageDigest.getInstance("SHA-256")
    val inputStream = getChannel(request).toInputStream()
    return DigestInputStream(inputStream, digest)
}

/**
 * Prevent DNS requests.
 * Important when proxying all requests over Tor to not leak DNS queries.
 */
private class NoDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return listOf(InetAddress.getByAddress(hostname, ByteArray(4)))
    }
}
