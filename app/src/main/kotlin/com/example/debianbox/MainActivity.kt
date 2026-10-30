package com.example.debianbox

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.OutputStream
import kotlin.concurrent.thread

class MainActivity : android.app.Activity() {

    private lateinit var debian: Debian
    private lateinit var term: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var web: WebView
    private lateinit var termPanel: LinearLayout

    private var shellProc: Process? = null
    private var shellIn: OutputStream? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debian = Debian(this)

        // ---------- Интерфейс (без XML) ----------
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnInstall = Button(this).apply { text = "Установить" }
        val btnTerm = Button(this).apply { text = "Терминал" }
        val btnGui = Button(this).apply { text = "Рабочий стол" }
        for (b in listOf(btnInstall, btnTerm, btnGui)) bar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        // Терминал
        termPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        term = TextView(this).apply {
            setTextColor(Color.rgb(0, 255, 70)); typeface = Typeface.MONOSPACE; textSize = 12f
            setTextIsSelectable(true); setPadding(12, 8, 12, 8)
        }
        scroll = ScrollView(this).apply { addView(term) }
        termPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "$ команда"
            typeface = Typeface.MONOSPACE; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { v, _, ev ->
                if (ev == null || ev.action == KeyEvent.ACTION_DOWN) { sendLine(v.text.toString()); v.text = ""; true } else false
            }
        }
        termPanel.addView(input, LinearLayout.LayoutParams(-1, -2))
        root.addView(termPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        // Рабочий стол (noVNC в WebView)
        web = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // ---------- Кнопки ----------
        btnInstall.setOnClickListener { installDebian() }
        btnTerm.setOnClickListener { showTerminal() }
        btnGui.setOnClickListener { startDesktop() }

        print(if (debian.installed) "Debian уже установлен. Нажмите «Терминал».\n"
              else "Нажмите «Установить», чтобы скачать Debian (~150–300 МБ).\n")
        if (debian.installed) startShell()
    }

    // ---------- Терминал ----------
    private fun print(s: String) = runOnUiThread {
        term.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showTerminal() {
        web.visibility = View.GONE; termPanel.visibility = View.VISIBLE
        if (shellProc == null && debian.installed) startShell()
    }

    private fun startShell() {
        if (shellProc != null) return
        thread {
            try {
                val p = debian.shell().start()
                shellProc = p; shellIn = p.outputStream
                val r = p.inputStream.bufferedReader()
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf); if (n < 0) break
                    print(String(buf, 0, n).replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), ""))
                }
                print("\n[оболочка завершена]\n")
            } catch (e: Exception) { print("Ошибка запуска: ${e.message}\n") }
            shellProc = null; shellIn = null
        }
    }

    private fun sendLine(line: String) {
        print("$ $line\n")
        thread { runCatching { shellIn?.apply { write((line + "\n").toByteArray()); flush() } } }
    }

    // ---------- Установка ----------
    private fun installDebian() {
        showTerminal()
        thread {
            try { debian.install { print(it) }; startShell() }
            catch (e: Exception) { print("Ошибка установки: ${e}\n") }
        }
    }

    // ---------- Графика ----------
    private fun startDesktop() {
        if (!debian.installed) { print("Сначала установите Debian.\n"); return }
        showTerminal()
        print("Запускаю рабочий стол (при первом запуске ставится XFCE — это долго)…\n")
        thread {
            try {
                val p = debian.command("/bin/bash", "-c", debian.desktopScript).start()
                var ready = false
                p.inputStream.bufferedReader().forEachLine { l ->
                    print(l + "\n"); if (l.contains("DESKTOP_READY")) ready = true
                }
                p.waitFor()
                if (ready) runOnUiThread {
                    termPanel.visibility = View.GONE; web.visibility = View.VISIBLE
                    web.loadUrl("http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=scale")
                } else print("Не удалось запустить рабочий стол.\n")
            } catch (e: Exception) { print("Ошибка: ${e.message}\n") }
        }
    }

    override fun onDestroy() { shellProc?.destroy(); super.onDestroy() }
}
