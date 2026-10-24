package com.learnhive.latexstudio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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

                if (!archiveFile.exists()) {
                    appendLog("[-] ERROR: engine_core.tar.xz not found in Documents/MyNotes!")
                    return@launch
                }

                if (!debianDir.exists() || debianDir.list().isNullOrEmpty()) {
                    appendLog("[*] Unpacking engine_core.tar.xz into private storage... (Wait 1-2 mins)")
                    debianDir.mkdirs()
                    extractTarXz(archiveFile, debianDir)
                    appendLog("[+] Rootfs extracted successfully!")
                } else {
                    appendLog("[+] Existing rootfs found. Skipping extraction.")
                }

                // Copy vector.tex into workspace
                val workDir = File(filesDir, "workspace").apply { mkdirs() }
                if (vectorTex.exists()) {
                    vectorTex.copyTo(File(workDir, "vector.tex"), overwrite = true)
                    appendLog("[+] Copied vector.tex to workspace.")
                }

                // Execute PRoot + LuaLaTeX
                appendLog("[*] Launching LuaLaTeX offline engine via PRoot...")
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
            BufferedInputStream(fis).use { bis ->
                XZInputStream(bis).use { xzis ->
                    TarArchiveInputStream(xzis).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val dest = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                dest.mkdirs()
                            } else {
                                dest.parentFile?.mkdirs()
                                FileOutputStream(dest).use { fos ->
                                    tar.copyTo(fos)
                                }
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
            appendLog("\n[SUCCESS] PDF Generated Successfully! Exit Code: 0")
            // Copy back output PDF to Documents/MyNotes
            val outPdf = File(workDir, "vector.pdf")
            if (outPdf.exists()) {
                val destPdf = File("/storage/emulated/0/Documents/MyNotes/output_app_vector.pdf")
                outPdf.copyTo(destPdf, overwrite = true)
                appendLog("[+] Output PDF copied to: Documents/MyNotes/output_app_vector.pdf")
            }
        } else {
            appendLog("\n[-] Compilation Failed with exit code: $exitCode")
        }
    }
}
