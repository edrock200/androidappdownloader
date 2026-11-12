package io.github.edrock200.appdownloader.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.ZipException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** A secure (https) link redirected to an unencrypted (http) address. */
class InsecureRedirectException : IOException("The link redirected to an unencrypted (http) address, so it was stopped")

/** Turns exceptions into short, plain messages for cards and snackbars (pure, for tests). */
object Errors {

    /** True for failures caused by having no working connection (not by the website). */
    fun isConnectivity(e: Throwable): Boolean = e is UnknownHostException || e is ConnectException ||
        e is NoRouteToHostException || e is SocketTimeoutException ||
        (e.cause?.let { it !== e && isConnectivity(it) } ?: false)

    fun isNoSpace(e: Throwable): Boolean {
        val m = (e.message.orEmpty() + " " + e.cause?.message.orEmpty())
        return "ENOSPC" in m || "No space left" in m
    }

    fun friendly(e: Throwable): String = when {
        e is InsecureRedirectException -> e.message!!
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ->
            "No internet connection, or the website can't be reached"
        e is SocketTimeoutException -> "The website took too long to respond"
        e is SSLHandshakeException || e is SSLPeerUnverifiedException ->
            "Couldn't make a secure connection (this device doesn't trust the website's certificate)"
        isNoSpace(e) -> "Not enough storage space"
        e is ZipException -> "The file is damaged or isn't an app"
        !e.message.isNullOrBlank() -> e.message!!
        e.cause != null && e.cause !== e -> friendly(e.cause!!)
        else -> e.javaClass.simpleName
    }
}
