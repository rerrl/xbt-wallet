package com.xbtwallet.app.electrum

import java.io.IOException

/**
 * The raw-socket errno a failed dial reports, when the OS named one.
 *
 * On the JVM a refused connection is a [java.net.ConnectException] with the
 * message `Connection refused` and nothing more, which is why the client used
 * to treat *every* `ConnectException` as one. On Android the same exception
 * carries the errno the kernel returned, and that word is the whole story:
 * `EACCES`/`EPERM` is the OS refusing the app a socket on *this device* — the
 * GrapheneOS Network permission, VPN lockdown mode, a firewall app, Data Saver
 * — and the server never heard the request at all. Telling that user to go
 * check their server is sending them to the wrong machine.
 *
 * Read this as a small, closed vocabulary rather than a general errno table:
 * it lists exactly the failures the Server screen and the signer screen have
 * wording for, and anything else keeps the type-based wording it had before.
 */
enum class SocketErrno {
    /** `EACCES`/`EPERM` — the OS denied the process a socket. A phone-side cause. */
    PERMISSION,

    /** `ECONNREFUSED` — the host is up and answered, refusing the port. */
    CONNECTION_REFUSED,

    /** `EHOSTUNREACH` — no route to that host from here. */
    HOST_UNREACHABLE,

    /** `ENETUNREACH` — the device has no route out to the network at all. */
    NETWORK_UNREACHABLE,

    /** `ETIMEDOUT` — the dial itself ran out of time (distinct from a read timeout). */
    CONNECT_TIMED_OUT,
}

/**
 * The errno behind [error], or null when nothing in its cause chain names one.
 *
 * Messages are read top down through `cause`, because which layer carries the
 * errno differs by route: a direct dial hands it back on the `ConnectException`
 * itself, while a wrapped failure (a transport that re-throws, a `SocketException`
 * hung off a `ConnectException`) only carries it one or two links down. See
 * [SocketErrno] for why the tokens matter.
 *
 * Only messages of exceptions the *kernel* can fail — [java.io.IOException] and
 * its subclasses (`ConnectException`, `SocketException`, `UnknownHostException`)
 * — are read, and only as a plain substring test on the uppercased text. An
 * errno is what a socket syscall returns, so that is where it can appear; a
 * protocol error ([ElectrumException]) carries the server's own prose and is
 * never a socket errno, however the reply happens to be worded. No token, or no
 * socket-level exception to carry one, means null — the caller keeps the wording
 * it has always had.
 */
internal fun socketErrnoOf(error: Throwable): SocketErrno? {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_ERRNO_CAUSE_DEPTH) {
        val message = current.message?.uppercase()
        if (current is IOException && message != null) {
            errnoIn(message)?.let { return it }
        }
        current = current.cause
        depth++
    }
    return null
}

/**
 * The reason a socket errno gets, in words a user can act on.
 *
 * One phrasing for both screens on purpose: the Server screen's connection test
 * logs it and the signer screen's balance error puts it in a sentence, and a
 * user comparing the two must not see two different stories about one failure.
 * The [SocketErrno.PERMISSION] case is the one that matters most — it is the
 * only reason here whose fix is on the phone rather than on the server, so it
 * names every on-device cause a wallet has been reported failing under.
 */
internal fun socketErrnoReason(errno: SocketErrno): String = when (errno) {
    SocketErrno.PERMISSION ->
        "the OS denied the connection (permission): check the app's Network permission, " +
            "VPN lockdown mode, a firewall app, or Data Saver"
    SocketErrno.CONNECTION_REFUSED -> "connection refused"
    SocketErrno.HOST_UNREACHABLE -> "host unreachable (no route to that host)"
    SocketErrno.NETWORK_UNREACHABLE -> "network unreachable (no route to that network)"
    SocketErrno.CONNECT_TIMED_OUT -> "the connection attempt timed out"
}

/**
 * The tokens [socketErrnoOf] looks for, checked in this order.
 *
 * The order is about substring overlap, not preference: `ENETUNREACH` does not
 * contain `EHOSTUNREACH` and vice versa, but keeping the permission tokens
 * first states plainly that a message naming both a permission and a network
 * failure (VPN lockdown reports `EACCES`, a firewall `EPERM`) is a permission
 * failure.
 */
private fun errnoIn(uppercasedMessage: String): SocketErrno? = when {
    uppercasedMessage.contains("EACCES") || uppercasedMessage.contains("EPERM") -> SocketErrno.PERMISSION
    uppercasedMessage.contains("ECONNREFUSED") -> SocketErrno.CONNECTION_REFUSED
    uppercasedMessage.contains("EHOSTUNREACH") -> SocketErrno.HOST_UNREACHABLE
    uppercasedMessage.contains("ENETUNREACH") -> SocketErrno.NETWORK_UNREACHABLE
    uppercasedMessage.contains("ETIMEDOUT") -> SocketErrno.CONNECT_TIMED_OUT
    else -> null
}

/**
 * How far down a cause chain [socketErrnoOf] looks. Four is what the log line
 * already prints, and a socket errno deeper than that is not a failure any
 * wording here would help with.
 */
private const val MAX_ERRNO_CAUSE_DEPTH = 4
