package io.github.iampopovich.eculense.core.obd

import java.io.IOException

/**
 * Why an OBD connection or command failed. Typed so the UI renders it in the app language (:core:obdtext);
 * [technicalMessage] is the English text for logs and exception messages.
 */
sealed interface ObdError {
    /** The adapter did not answer [command] in time. */
    data class Timeout(val command: String) : ObdError

    /** No transport factory is registered for the endpoint [kind]. */
    data class NoTransport(val kind: String) : ObdError

    /** The adapter found no vehicle protocol; [detail] is the adapter's reply. */
    data class NoProtocol(val detail: String) : ObdError

    /** The ECU does not answer (ignition off?). */
    data object EcuSilent : ObdError

    /** K-Line bus initialization failed ("BUS INIT: ...ERROR"): ignition off or the ECU did not wake up. */
    data object BusInitFailed : ObdError

    /** The adapter answered [command] with the error reply [detail]. */
    data class CommandFailed(val command: String, val detail: String) : ObdError

    /** The adapter closed the connection (stream ended). */
    data object AdapterClosed : ObdError

    /** Write to a transport that is not open. */
    data object NotOpen : ObdError

    /** A serial adapter answered at none of the [tried] baud rates. */
    data class NoBaudRate(val tried: List<Int>) : ObdError

    /** The demo adapter was disconnected. */
    data object DemoDisconnected : ObdError

    /** The device has no Bluetooth adapter. */
    data object NoBluetooth : ObdError

    /** The USB device could not be opened. */
    data object UsbNoAccess : ObdError

    /** The user did not grant access to the USB adapter. */
    data object UsbPermissionDenied : ObdError

    /** The USB port is not open. */
    data object UsbNotOpen : ObdError

    /** The USB adapter is no longer attached. */
    data object UsbDetached : ObdError

    /** The link dropped while connected; [cause] is what the transport reported. */
    data class LinkLost(val cause: ObdError) : ObdError

    /** Any other failure; [detail] is the technical message, shown as is. */
    data class Other(val detail: String) : ObdError

    val technicalMessage: String
        get() = when (this) {
            is Timeout -> "adapter did not answer '$command'"
            is NoTransport -> "no transport '$kind'"
            is NoProtocol -> "adapter found no protocol: $detail"
            EcuSilent -> "ECU does not respond"
            BusInitFailed -> "bus init failed (BUS INIT: ERROR)"
            is CommandFailed -> "$command → $detail"
            AdapterClosed -> "adapter closed the connection"
            NotOpen -> "transport is not open"
            is NoBaudRate -> "ELM327 does not respond at any baud rate: $tried"
            DemoDisconnected -> "demo adapter disconnected"
            NoBluetooth -> "no Bluetooth on this device"
            UsbNoAccess -> "no access to the USB device"
            UsbPermissionDenied -> "USB access not granted"
            UsbNotOpen -> "USB port is not open"
            UsbDetached -> "USB adapter detached"
            is LinkLost -> "link to the adapter lost: ${cause.technicalMessage}"
            is Other -> detail
        }

    companion object {
        /** The typed error behind [e]; unknown exceptions keep their message as [Other]. */
        fun of(e: Throwable): ObdError = when (e) {
            is ObdException -> e.error
            is ObdIoException -> e.error
            else -> Other(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}

/** Adapter/protocol failure of a single command or of the session setup. */
open class ObdException(val error: ObdError, cause: Throwable? = null) : Exception(error.technicalMessage, cause)

class ElmTimeoutException(command: String) : ObdException(ObdError.Timeout(command))

/** Transport failure: an IOException (the transport contract) that carries a typed error. */
class ObdIoException(val error: ObdError, cause: Throwable? = null) : IOException(error.technicalMessage, cause)
