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
import kotlinx.coroutines.delay
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
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView
    private lateinit var btnSetupEngine: Button
    private lateinit var btnQuickTest: Button
    private lateinit var btnCompileVector: Button

    private val logQueue = StringBuilder()
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }

        val titleView = TextView(this).apply {
            text = "Learn Hive LaTeX Studio\nOffline Engine Console"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(titleView)

        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, 20)
        }
        layout.addView(statusView)

        btnSetupEngine = Button(this).apply {
            text = "1. Setup / Extract Engine (Wait 1-2 mins)"
            setOnClickListener {
                if (checkPermission()) startEngineExtraction() else requestPermission()
            }
        }
        layout.addView(btnSetupEngine)

        btnQuickTest = Button(this).apply {
            text = "2. Quick Test (Hello World PDF)"
            setOnClickListener {
                if (checkPermission()) runCompilation(isQuickTest = true) else requestPermission()
            }
        }
        layout.addView(btnQuickTest)

        btnCompileVector = Button(this).apply {
            text = "3. Compile Heavy (vector.tex)"
            setOnClickListener {
                if (checkPermission()) runCompilation(isQuickTest = false) else requestPermission()
            }
        }
        layout.addView(btnCompileVector)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        logView = TextView(this).apply {
            text = "System Initialized.\n"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        }
        scrollView.addView(logView)
        layout.addView(scrollView)

        setContentView(layout)

        if (!checkPermission()) requestPermission()
        startLogDispatcher()
        refreshEngineStatus()
    }

    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun isEngineReady(): Boolean {
        val debianDir = File(filesDir, "debian")
        val markerFile = File(debianDir, ".extracted_posix_ok")
        return debianDir.exists() && markerFile.exists()
    }

    private fun refreshEngineStatus() {
        if (isEngineReady()) {
            statusView.text = "Engine Status: Ready ✅"
            btnSetupEngine.isEnabled = false
            btnSetupEngine.text = "Engine Installed & Ready ✅"
            btnQuickTest.isEnabled = !isBusy
            btnCompileVector.isEnabled = !isBusy
        } else {
            statusView.text = "Engine Status: Not Extracted ❌ (Tap Button 1 below)"
            btnSetupEngine.isEnabled = !isBusy
            btnSetupEngine.text = "1. Setup / Extract Engine (Only Needed Once)"
            btnQuickTest.isEnabled = false
            btnCompileVector.isEnabled = false
        }
    }

    private fun appendLog(msg: String) {
        synchronized(logQueue) {
            logQueue.append(msg).append("\n")
        }
    }

    private fun startLogDispatcher() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                delay(120)
                var textToAppend = ""
                synchronized(logQueue) {
                    if (logQueue.isNotEmpty()) {
                        textToAppend = logQueue.toString()
                        logQueue.clear()
                    }
                }
                if (textToAppend.isNotEmpty()) {
                    logView.append(textToAppend)
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun startEngineExtraction() {
        if (isBusy) return
        isBusy = true
        btnSetupEngine.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val debianDir = File(filesDir, "debian")
                val archiveFile = File("/storage/emulated/0/Documents/MyNotes/engine_core.tar.xz")
                val markerFile = File(debianDir, ".extracted_posix_ok")

                if (!archiveFile.exists()) {
                    appendLog("[-] ERROR: engine_core.tar.xz not found in Documents/MyNotes!")
                    return@launch
                }

                appendLog("[*] Starting extraction with POSIX symlinks & permissions...")
                appendLog("[*] Please wait 1-2 minutes, do not close the app...")
                debianDir.deleteRecursively()
                debianDir.mkdirs()

                extractTarXz(archiveFile, debianDir)
                markerFile.createNewFile()
                appendLog("[+] Extraction complete! Engine is ready to compile.")

            } catch (e: Exception) {
                appendLog("[-] EXTRACTION FAILED: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    refreshEngineStatus()
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

    private fun runCompilation(isQuickTest: Boolean) {
        if (isBusy) return
        isBusy = true
        btnQuickTest.isEnabled = false
        btnCompileVector.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val debianDir = File(filesDir, "debian")
                val workDir = File(filesDir, "workspace").apply { mkdirs() }
                val targetTexName = if (isQuickTest) "quick_test.tex" else "vector.tex"

                if (isQuickTest) {
                    val quickCode = """
                        \documentclass{article}
                        \usepackage{amsmath}
                        \begin{document}
                        \Huge Hello from Learn Hive Mobile!
                        \Large
                        \par Offline LuaLaTeX on Android (Poco X6).
                        \par \textbf{Math:} $ E = mc^2 \quad \int_0^\infty e^{-x} dx = 1 $
                        \end{document}
                    """.trimIndent()
                    File(workDir, targetTexName).writeText(quickCode)
                    appendLog("[*] Prepared quick_test.tex")
                } else {
                    val vectorTex = File("/storage/emulated/0/Documents/MyNotes/vector.tex")
                    if (!vectorTex.exists()) {
                        appendLog("[-] ERROR: vector.tex not found in Documents/MyNotes!")
                        return@launch
                    }
                    vectorTex.copyTo(File(workDir, targetTexName), overwrite = true)
                    appendLog("[*] Prepared vector.tex in workspace.")
                }

                appendLog("[*] Launching LuaLaTeX for $targetTexName...")
                executeLuaLaTeX(debianDir, workDir, targetTexName)

            } catch (e: Exception) {
                appendLog("[-] CRASH: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    refreshEngineStatus()
                }
            }
        }
    }

    private fun executeLuaLaTeX(rootfs: File, workDir: File, texFileName: String) {
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
            texFileName
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
        val pdfName = texFileName.substringBeforeLast(".") + ".pdf"
        val outPdf = File(workDir, pdfName)

        if (exitCode == 0 && outPdf.exists()) {
            appendLog("\n========================================")
            appendLog("[SUCCESS] PDF Created Successfully! 🎉")
            appendLog("========================================")
            val destPdf = File("/storage/emulated/0/Documents/MyNotes/APP_GENERATED_$pdfName")
            outPdf.copyTo(destPdf, overwrite = true)
            appendLog("[+] Saved to: Documents/MyNotes/APP_GENERATED_$pdfName")
        } else {
            appendLog("\n[-] Compilation finished with exit code: $exitCode")
            if (outPdf.exists()) {
                val destPdf = File("/storage/emulated/0/Documents/MyNotes/APP_GENERATED_$pdfName")
                outPdf.copyTo(destPdf, overwrite = true)
                appendLog("[+] PDF saved to: Documents/MyNotes/APP_GENERATED_$pdfName")
            }
        }
    }
}
