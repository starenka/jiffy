package cz.starenka.jiffy

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        WifiLan.watch(this)
        val home = filesDir.toPath()
        org.apache.sshd.common.util.io.PathUtils.setUserHomeFolderResolver { home }
        // Android ships a stripped-down "BC"; replace it with the full one MINA SSHD expects.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
