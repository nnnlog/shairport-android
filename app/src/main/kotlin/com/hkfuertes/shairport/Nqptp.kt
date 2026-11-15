package com.hkfuertes.shairport

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.net.InetAddress

/**
 * NQPTP, AirPlay 2's timing service: libnqptp.so, an executable among the APK's native libraries
 * (extracted on install, so it can run), which ReceiverService runs as a child process. It binds
 * UDP ports 319 and 320 (PTP). Linux keeps ports below 1024 for root, but Android opens a few
 * well-known ones to apps, PTP's among them: Android 17 does.
 */
object Nqptp {
    /** Its shared memory, in the directory given as NQPTP_SHM_DIRECTORY (patch nqptp/0002). */
    const val SHARED_MEMORY = "nqptp"

    private val PORTS = intArrayOf(319, 320)
    private val ANY: InetAddress = InetAddress.getByAddress(ByteArray(4))

    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libnqptp.so")

    /**
     * Whether this app may bind PTP's ports: refused, AirPlay 2 can't work on this device. In use
     * counts as open: NQPTP has them. Probed once per process, so before NQPTP first starts: it
     * can't change without a reboot, and a probe as NQPTP binds would take a port from under it.
     */
    val portsOpen: Boolean by lazy { PORTS.all(::bindable) }

    private fun bindable(port: Int): Boolean =
        try {
            val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)
            try {
                Os.bind(socket, ANY, port)
            } finally {
                Os.close(socket)
            }
            true
        } catch (error: ErrnoException) {
            // EACCES: no CAP_NET_BIND_SERVICE and not on Android's list; EPERM: an eBPF bind filter.
            error.errno != OsConstants.EACCES && error.errno != OsConstants.EPERM
        }
}
