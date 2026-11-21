package com.min.assetripper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/** Runs the bundled server as a child process in a foreground service. */
class ServerService : Service() {

    companion object {
        const val ACTION_START = "com.min.assetripper.START"
        const val ACTION_STOP = "com.min.assetripper.STOP"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, ServerService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ServerService::class.java))
        }
    }

    @Volatile private var proc: Process? = null
    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        createChannel()
        startForeground(NOTIF_ID, notification(getString(R.string.status_starting)))
        stopping = false
        ServerState.clearLog()
        worker = Thread({ runServer() }, "assetripper-server").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        val p = proc
        if (p != null) {
            p.destroy()
            Thread {
                try {
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (e: InterruptedException) {
                }
            }.start()
        }
        releaseWake()
        if (ServerState.phase != ServerState.Phase.ERROR) ServerState.set(ServerState.Phase.STOPPED)
        super.onDestroy()
    }

    private fun runServer() {
        val prefs = Prefs(this)
        try {
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                throw IllegalStateException(getString(R.string.err_abi))
            }

            ServerState.updateProgress(0)
            ServerState.set(ServerState.Phase.INSTALLING)
            BinaryInstaller.install(this) { p ->
                ServerState.updateProgress(p)
                updateNotification(getString(R.string.status_installing, p))
            }
            if (stopping) return

            killStale()
            val port = if (prefs.port in 1..65535) prefs.port else freePort()
            ServerState.port = port
            ServerState.set(ServerState.Phase.STARTING)
            updateNotification(getString(R.string.status_starting))

            val home = File(filesDir, "home").apply { mkdirs() }
            val tmp = File(cacheDir, "tmp").apply { mkdirs() }
            val pidFile = File(filesDir, "server.pid")
            val exe = BinaryInstaller.exe(this)

            val script = "echo \$\$ > \"\$1\"; exec \"\$2\" --port \"\$3\" --headless"
            val pb = ProcessBuilder("/system/bin/sh", "-c", script, "sh", pidFile.path, exe.path, port.toString())
            pb.directory(home)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
            env["DOTNET_gcServer"] = "0"
            env["DOTNET_TieredPGO"] = "0"
            env["ASSETRIPPER_ANDROID"] = "1"
            env["CLR_OPENSSL_VERSION_OVERRIDE"] = "3"
            env["LD_LIBRARY_PATH"] = listOfNotNull(exe.parent, env["LD_LIBRARY_PATH"]).joinToString(":")
            env["HOME"] = home.path
            env["TMPDIR"] = tmp.path
            if (prefs.heapMb > 0) {
                env["DOTNET_GCHeapHardLimit"] = java.lang.Long.toHexString(prefs.heapMb.toLong() * 1024L * 1024L)
            }

            val p = pb.start()
            proc = p
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { ServerState.log(it) }
                } catch (e: IOException) {
                }
            }.apply { isDaemon = true }.start()

            val deadline = System.currentTimeMillis() + 90_000L
            var ready = false
            while (!stopping && p.isAlive && System.currentTimeMillis() < deadline) {
                if (probe(port)) {
                    ready = true
                    break
                }
                Thread.sleep(500)
            }
            if (stopping) return
            if (!ready) {
                if (!p.isAlive) throw IllegalStateException(getString(R.string.err_exec, "exit " + p.exitValue()))
                throw IllegalStateException(getString(R.string.err_timeout))
            }

            if (prefs.wakeLock) acquireWake()
            ServerState.set(ServerState.Phase.RUNNING)
            updateNotification(getString(R.string.notif_text, port))

            val code = p.waitFor()
            pidFile.delete()
            if (!stopping) {
                throw IllegalStateException(getString(R.string.err_exec, "exit $code"))
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            ServerState.log("ERROR: $msg")
            ServerState.set(ServerState.Phase.ERROR, msg)
        } finally {
            if (!stopping) stopSelf()
        }
    }

    /** Kills a server left over from an earlier run. */
    private fun killStale() {
        val f = File(filesDir, "server.pid")
        val pid = try {
            f.readText().trim().toIntOrNull()
        } catch (e: IOException) {
            null
        }
        if (pid != null) {
            val cmd = try {
                File("/proc/$pid/cmdline").readText()
            } catch (e: IOException) {
                ""
            }
            if (cmd.contains(BinaryInstaller.EXE)) {
                android.os.Process.killProcess(pid)
                Thread.sleep(500)
            }
        }
        f.delete()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun probe(port: Int): Boolean = try {
        val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 1500
        c.instanceFollowRedirects = false
        val ok = c.responseCode > 0
        c.disconnect()
        ok
    } catch (e: IOException) {
        false
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assetripper:server").also { it.acquire() }
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launch)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification(text))
    }
}
