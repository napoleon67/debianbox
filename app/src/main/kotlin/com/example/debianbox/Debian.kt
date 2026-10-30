package com.example.debianbox

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Установка и запуск настоящего Debian (rootfs) через proot.
 *
 * Ожидается, что в app/src/main/jniLibs/arm64-v8a лежат (из пакетов Termux):
 *   libproot.so         (бинарник proot)
 *   libproot-loader.so  (proot loader)
 *   libtalloc.so        (libtalloc.so.2 — переименована, т.к. в jniLibs нужны имена lib*.so)
 */
class Debian(private val ctx: Context) {

    companion object {
        // Rootfs Debian для arm64 из проекта proot-distro.
        // ПРОВЕРЬТЕ актуальную ссылку на https://github.com/termux/proot-distro/releases
        const val ROOTFS_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.18.0/debian-trixie-aarch64-pd-v4.18.0.tar.xz"
    }

    private val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    val rootfs = File(ctx.filesDir, "debian")
    private val libDir = File(ctx.filesDir, "lib")
    private val tmpDir = File(ctx.filesDir, "tmp")

    val installed: Boolean get() = File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()

    /** Скачивает и распаковывает rootfs. log() вызывается из фонового потока. */
    fun install(log: (String) -> Unit) {
        libDir.mkdirs(); tmpDir.mkdirs()
        // proot ищет libtalloc.so.2
        File(nativeDir, "libtalloc.so").copyTo(File(libDir, "libtalloc.so.2"), overwrite = true)

        val archive = File(ctx.cacheDir, "rootfs.tar.xz")
        log("Скачиваю Debian…\n")
        val conn = URL(ROOTFS_URL).openConnection()
        val total = conn.contentLengthLong
        var done = 0L
        var lastPct = -1
        conn.getInputStream().use { inp ->
            archive.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct / 10 != lastPct / 10) { lastPct = pct; log("  $pct%\n") }
                    }
                }
            }
        }

        log("Распаковываю (несколько минут)…\n")
        rootfs.deleteRecursively(); rootfs.mkdirs()
        TarArchiveInputStream(XZInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            var e: TarArchiveEntry? = tar.nextTarEntry
            while (e != null) {
                extract(tar, e)
                e = tar.nextTarEntry
            }
        }
        archive.delete()

        // Базовая настройка
        File(rootfs, "etc/resolv.conf").apply { parentFile?.mkdirs(); writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n") }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n")
        log("Debian установлен.\n")
    }

    private fun extract(tar: TarArchiveInputStream, e: TarArchiveEntry) {
        // В архиве proot-distro есть верхний каталог "debian-…/" — отрезаем его
        val parts = e.name.split("/").filter { it.isNotEmpty() && it != "." }
        if (parts.size <= 1) return
        val rel = parts.drop(1).joinToString("/")
        val target = File(rootfs, rel)
        if (!target.canonicalPath.startsWith(rootfs.canonicalPath)) return // защита от ../

        when {
            e.isDirectory -> { target.mkdirs(); target.setReadable(true, false); target.setWritable(true, true); target.setExecutable(true, false) }
            e.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                runCatching { Files.deleteIfExists(target.toPath()) }
                Files.createSymbolicLink(target.toPath(), Paths.get(e.linkName))
            }
            e.isLink -> {
                val src = File(rootfs, e.linkName.split("/").filter { it.isNotEmpty() && it != "." }.drop(1).joinToString("/"))
                target.parentFile?.mkdirs()
                if (src.exists()) src.copyTo(target, overwrite = true)
            }
            e.isFile -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
                val mode = e.mode
                target.setReadable(true, false)
                target.setWritable(true, true)
                if (mode and 0b001_001_001 != 0) target.setExecutable(true, false)
            }
        }
    }

    /** Команда запуска: proot + Debian + произвольная команда внутри. */
    fun command(vararg inner: String): ProcessBuilder {
        val args = mutableListOf(
            File(nativeDir, "libproot.so").path,
            "--link2symlink", "-0",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root"
        )
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
        args += inner
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("PROOT_TMP_DIR", tmpDir.path)
            put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
            put("PROOT_NO_SECCOMP", "1")
            put("LD_LIBRARY_PATH", libDir.path)
        }
        return pb
    }

    fun shell(): ProcessBuilder = command("/bin/bash", "-l")

    /** Скрипт установки и запуска рабочего стола XFCE + VNC + noVNC (порт 6080). */
    val desktopScript: String = """
        set -e
        if ! command -v vncserver >/dev/null 2>&1; then
          apt-get update
          apt-get install -y --no-install-recommends xfce4 xfce4-terminal dbus-x11 \
            tigervnc-standalone-server novnc websockify fonts-noto firefox-esr
        fi
        mkdir -p /root/.vnc
        cat > /root/.vnc/xstartup <<'XEOF'
        #!/bin/sh
        unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS
        exec dbus-launch --exit-with-session startxfce4
        XEOF
        chmod +x /root/.vnc/xstartup
        vncserver -kill :1 >/dev/null 2>&1 || true
        rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
        vncserver :1 -geometry 1280x720 -localhost yes -SecurityTypes None
        nohup websockify --web /usr/share/novnc 6080 localhost:5901 >/tmp/novnc.log 2>&1 &
        echo DESKTOP_READY
    """.trimIndent()
}
