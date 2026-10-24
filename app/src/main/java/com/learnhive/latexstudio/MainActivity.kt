package com.learnhive.latexstudio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.system.Os
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        val titleView = TextView(this).apply {
            text = "Learn Hive LaTeX Studio\nOffline Engine Console"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(titleView)

        actionButton = Button(this).apply {
            text = "Setup Engine & Compile vector.tex"
            setOnClickListener {
                if (checkPermission()) {
                    startSetupAndCompile()
                } else {
                    requestPermission()
                }
            }
        }
        layout.addView(actionButton)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        logView = TextView(this).apply {
            text = "Logs will appear here...\nReady."
            textSize = 14f
            setPadding(0, 24, 0, 0)
        }
        scrollView.addView(logView)
        layout.addView(scrollView)

        setContentView(layout)

        if (!checkPermission()) {
            requestPermission()
        }
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun appendLog(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            logView.append("\n" + msg)
        }
    }

    private fun startSetupAndCompile() {
        actionButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val debianDir = File(filesDir, "debian")
                val archiveFile = File("/storage/emulated/0/Documents/MyNotes/engine_core.tar.xz")
                val vectorTex = File("/storage/emulated/0/Documents/MyNotes/vector.tex")
                val markerFile = File(debianDir, ".extracted_posix_ok")

                if (!archiveFile.exists()) {
                    appendLog("[-] ERROR: engine_core.tar.xz not found in Documents/MyNotes!")
                    return@launch
                }

                // Clean re-extract with full POSIX permissions
                if (!markerFile.exists()) {
                    appendLog("[*] Extracting with real POSIX +x & Symlinks (Please wait 1-2 mins)...")
                    debianDir.deleteRecursively()
                    debianDir.mkdirs()
                    extractTarXz(archiveFile, debianDir)
                    markerFile.createNewFile()
                    appendLog("[+] Rootfs extracted with full executable permissions!")
                } else {
                    appendLog("[+] POSIX Rootfs ready.")
                }

                // Workspace setup
                val workDir = File(filesDir, "workspace").apply { mkdirs() }
                if (vectorTex.exists()) {
                    vectorTex.copyTo(File(workDir, "vector.tex"), overwrite = true)
                    appendLog("[+] Prepared vector.tex in workspace.")
                }

                // Launch Engine
                appendLog("[*] Launching LuaLaTeX...")
                runLuaLaTeX(debianDir, workDir)

            } catch (e: Exception) {
                appendLog("[-] CRASH: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    actionButton.isEnabled = true
                }
            }
        }
    }

    private fun extractTarXz(archive: File, targetDir: File) {
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis, 65536).use { bis ->
                XZInputStream(bis).use { xzis ->
                    TarArchiveInputStream(xzis).use { tar ->
                        var entry = tar.nextEntry
                        var count = 0
                        while (entry != null) {
                            val dest = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                dest.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                dest.parentFile?.mkdirs()
                                dest.delete()
                                try {
                                    Os.symlink(entry.linkName, dest.absolutePath)
                                } catch (ignored: Exception) {
                                }
                            } else {
                                dest.parentFile?.mkdirs()
                                FileOutputStream(dest).use { fos ->
                                    tar.copyTo(fos)
                                }
                                val mode = entry.mode
                                if (mode > 0) {
                                    try {
                                        Os.chmod(dest.absolutePath, mode)
                                    } catch (e: Exception) {
                                        dest.setExecutable((mode and 73) != 0, false)
                                    }
                                }
                            }
                            count++
                            if (count % 3000 == 0) {
                                appendLog("[*] Extracted $count files...")
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun runLuaLaTeX(rootfs: File, workDir: File) {
        val nativeDir = applicationInfo.nativeLibraryDir
        val prootBin = File(nativeDir, "libproot.so").absolutePath
        val loaderBin = File(nativeDir, "libproot-loader.so").absolutePath
        val tmpDir = File(cacheDir, "proot_tmp").apply { mkdirs() }

        val cmd = listOf(
            prootBin,
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "${workDir.absolutePath}:/root",
            "-w", "/root",
            "/usr/bin/lualatex",
            "--interaction=nonstopmode",
            "vector.tex"
        )

        val pb = ProcessBuilder(cmd)
        pb.environment()["PROOT_LOADER"] = loaderBin
        pb.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = nativeDir
        pb.environment()["HOME"] = "/root"
        pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        pb.redirectErrorStream(true)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String? = reader.readLine()
        while (line != null) {
            appendLog(line)
            line = reader.readLine()
        }

        val exitCode = process.waitFor()
        if (exitCode == 0) {
            appendLog("\n========================================")
            appendLog("[SUCCESS] PDF Generated Successfully! 🎉")
            appendLog("========================================")
            val outPdf = File(workDir, "vector.pdf")
            if (outPdf.exists()) {
                val destPdf = File("/storage/emulated/0/Documents/MyNotes/app_vector_success.pdf")
                outPdf.copyTo(destPdf, overwrite = true)
                appendLog("[+] Saved to: Documents/MyNotes/app_vector_success.pdf")
            }
        } else {
            appendLog("\n[-] Compilation Failed with exit code: $exitCode")
        }
    }
}
