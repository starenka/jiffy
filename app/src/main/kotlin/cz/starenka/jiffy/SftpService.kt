package cz.starenka.jiffy

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.mina.MinaServiceFactoryFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import cz.starenka.jiffy.vfs.VFileSystem
import cz.starenka.jiffy.vfs.VProvider
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.PublicKey

class SftpService : Service() {
    companion object {
        private const val NETWORK = "wifiNetwork"
        private const val HOST = "wifiAddress"

        internal fun start(context: Context, endpoint: WifiEndpoint) {
            context.startForegroundService(Intent(context, SftpService::class.java)
                .putExtra(NETWORK, endpoint.network).putExtra(HOST, endpoint.host))
        }
    }

    private var startRequested = false
    private var stopping = false
    private var sshd: SshServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sftp", "SFTP sharing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "sftp")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("jiffy is sharing")
            .setContentText("Port ${Config.PORT}")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!startRequested) {
            startRequested = true
            val endpoint = WifiLan.endpoint
            if (endpoint == null) {
                stopWithError("No Wi-Fi connection")
            } else if (intent?.getStringExtra(HOST) != endpoint.host || intent.getLongExtra(NETWORK, 0) != endpoint.network) {
                stopWithError("Wi-Fi changed before sharing started. Check the address and try again.")
            } else {
                WifiLan.onChange = {
                    if (WifiLan.endpoint != endpoint) stopWithError("Wi-Fi changed. Start sharing again on the desired network.")
                }
                Thread({ startServer(endpoint) }, "sftp-start").start()
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized private fun stopWithError(message: String) {
        if (stopping) return
        stopping = true
        ServerState.error = message
        stopSelf()
        ServerState.changed()
    }

    private fun startServer(endpoint: WifiEndpoint) {
        try {
            val ctx = applicationContext
            val kp = HostKey.load(ctx)
            val s = SshServer.setUpDefaultServer()
            s.host = endpoint.host
            s.port = Config.PORT
            s.ioServiceFactoryFactory = MinaServiceFactoryFactory()
            s.keyPairProvider = KeyPairProvider.wrap(kp)
            s.userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
            s.keyboardInteractiveAuthenticator = KeyboardInteractiveAuthenticator.NONE
            s.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, session ->
                authorizedKeys(ctx, session).any { KeyUtils.compareKeys(it, key) }
            }
            s.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            s.shellFactory = null
            s.commandFactory = null
            s.fileSystemFactory = object : FileSystemFactory {
                override fun getUserHomeDir(session: SessionContext): Path? = null
                override fun createFileSystem(session: SessionContext): FileSystem =
                    VFileSystem(VProvider, Config.enabledShares(ctx))
            }
            synchronized(this) {
                if (stopping || WifiLan.endpoint != endpoint) return
                sshd = s
                s.start()
                acquireLocks()
                val fingerprint = HostKey.fingerprint(kp.public)
                advertise(endpoint, fingerprint)
                ServerState.fingerprint = fingerprint
                ServerState.error = null
                ServerState.boundHost = endpoint.host
            }
        } catch (t: Throwable) {
            Log.e("SftpService", "start failed", t)
            stopWithError(t.toString())
        }
        ServerState.changed()
    }

    // Lets `just mount` find the phone on any network via mDNS, without adb.
    private fun advertise(endpoint: WifiEndpoint, fingerprint: String) {
        val info = NsdServiceInfo().apply {
            // Per-phone name (host key fingerprint prefix) so several phones never collide or leak personal names.
            serviceName = "jiffy-" + fingerprint.removePrefix("SHA256:").filter { it.isLetterOrDigit() }.take(6)
            serviceType = "_ssh._tcp"
            port = Config.PORT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setNetwork(Network.fromNetworkHandle(endpoint.network))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i("SftpService", "mDNS registered ${i.serviceName}") }
            override fun onServiceUnregistered(i: NsdServiceInfo) { Log.i("SftpService", "mDNS unregistered ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w("SftpService", "mDNS registration failed: $code") }
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) { Log.w("SftpService", "mDNS unregistration failed: $code") }
        }
        try {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        } catch (t: Throwable) { Log.w("SftpService", "mDNS registration failed", t) }
    }

    // Unregistering sends an mDNS goodbye so clients drop the phone immediately instead of waiting for the TTL.
    private fun unadvertise() {
        val listener = nsdListener ?: return
        nsdListener = null
        try { getSystemService(NsdManager::class.java).unregisterService(listener) } catch (t: Throwable) { Log.w("SftpService", "mDNS unregister", t) }
    }

    private fun authorizedKeys(ctx: Context, session: SessionContext): List<PublicKey> =
        Config.authorizedKeyLines(ctx).mapNotNull {
            try {
                AuthorizedKeyEntry.parseAuthorizedKeyEntry(it).resolvePublicKey(session, emptyMap(), PublicKeyEntryResolver.IGNORING)
            } catch (e: Exception) { Log.w("SftpService", "bad authorized key line", e); null }
        }

    private fun acquireLocks() {
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jiffy:server").apply { acquire() }
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "jiffy").apply { acquire() }
    }

    @Synchronized override fun onDestroy() {
        stopping = true
        WifiLan.onChange = null
        unadvertise()
        try { sshd?.stop(true) } catch (t: Throwable) { Log.w("SftpService", "stop", t) }
        sshd = null
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        ServerState.boundHost = null
        ServerState.changed()
        super.onDestroy()
    }
}
