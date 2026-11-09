package az.zeng

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS client that trusts only Let's Encrypt's roots, bundled in res/raw.
 *  - Android 5/6 don't ship ISRG roots, so the system store can't verify our cert there.
 *  - Trusting nothing else pins the server: a rogue CA or a proxy can't impersonate it.
 */
object Net {
    private val roots = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2, R.raw.isrg_root_ye, R.raw.isrg_root_yr)

    private val trust: X509TrustManager by lazy {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        roots.forEachIndexed { i, id ->
            App.ctx.resources.openRawResource(id).use { ks.setCertificateEntry("root$i", cf.generateCertificate(it)) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    val http: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (BuildConfig.SERVER.startsWith("https")) {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            b.sslSocketFactory(ssl.socketFactory, trust)
        }
        b.build()
    }

    /** Long-lived socket: no read timeout, pings keep it alive. */
    val ws: OkHttpClient by lazy { http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build() }
}
