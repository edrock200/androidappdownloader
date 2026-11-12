package io.github.edrock200.appdownloader.net

import android.content.Context
import android.os.Build
import io.github.edrock200.appdownloader.R
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    const val USER_AGENT = "AppDownloader/1.0 (Android)"

    @Volatile
    private var appContext: Context? = null

    /** Lets the client load bundled certificates on old devices. Call from Application.onCreate. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Client for downloads (no overall time limit; large files take a while). */
    val client: OkHttpClient by lazy { build() }

    /** Client for small lookups (GitHub API, aftv.news, change checks): one slow site can't stall a check. */
    val metaClient: OkHttpClient by lazy { client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build() }

    private fun build(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val withAgent = if (req.header("User-Agent") == null) {
                    req.newBuilder().header("User-Agent", USER_AGENT).build()
                } else {
                    req
                }
                val response = chain.proceed(withAgent)
                // Redirects are followed inside proceed(); never let https quietly become http.
                if (req.url.isHttps && !response.request.url.isHttps) {
                    response.close()
                    throw InsecureRedirectException()
                }
                response
            }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) addLetsEncryptRoots(builder)
        return builder.build()
    }

    /**
     * Android before 7.1.1 doesn't trust Let's Encrypt's ISRG roots, which many download sites use.
     * Trust them in addition to the system certificates.
     */
    private fun addLetsEncryptRoots(builder: OkHttpClient.Builder) {
        val context = appContext ?: return
        runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().first()
            system.acceptedIssuers.forEachIndexed { i, cert -> keyStore.setCertificateEntry("system-$i", cert) }
            listOf(R.raw.isrgrootx1, R.raw.isrg_root_x2).forEachIndexed { i, res ->
                context.resources.openRawResource(res).use {
                    keyStore.setCertificateEntry("isrg-$i", factory.generateCertificate(it) as X509Certificate)
                }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            builder.sslSocketFactory(ssl.socketFactory, trustManager)
        }
    }

    /** Executes [request] on [using] and suspends until headers arrive. The caller must close the response. */
    suspend fun execute(request: Request, using: OkHttpClient = metaClient): Response = execute(using.newCall(request))

    /** Executes [call]; cancelling the coroutine cancels the call, and a response that arrives too late is closed. */
    suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _ -> response.close() }
            }
        })
    }
}
