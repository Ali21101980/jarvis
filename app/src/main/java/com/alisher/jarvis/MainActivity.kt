package com.alisher.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val DEFAULT_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
        const val SYSTEM_PROMPT =
            "Ты — Джарвис, голосовой помощник Алишера. Отвечай по-русски, " +
            "коротко и по делу (1–3 предложения), потому что ответ читается вслух. " +
            "Без markdown, списков и эмодзи."
        const val REQ_AUDIO = 1
    }

    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var editKey: EditText
    private lateinit var editUrl: EditText
    private lateinit var editModel: EditText
    private lateinit var editText: EditText

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false

    // История диалога (чтобы Джарвис помнил, о чём говорили)
    private val history = mutableListOf<JSONObject>()

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        status = findViewById(R.id.status)
        scroll = findViewById(R.id.scroll)
        editKey = findViewById(R.id.editKey)
        editUrl = findViewById(R.id.editUrl)
        editModel = findViewById(R.id.editModel)
        editText = findViewById(R.id.editText)
        val panel = findViewById<LinearLayout>(R.id.settingsPanel)

        editKey.setText(prefs.getString("key", ""))
        editUrl.setText(prefs.getString("url", DEFAULT_URL))
        editModel.setText(prefs.getString("model", DEFAULT_MODEL))
        if (prefs.getString("key", "").isNullOrBlank()) panel.visibility = View.VISIBLE

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.edit()
                .putString("key", editKey.text.toString().trim())
                .putString("url", editUrl.text.toString().trim().ifBlank { DEFAULT_URL })
                .putString("model", editModel.text.toString().trim().ifBlank { DEFAULT_MODEL })
                .apply()
            panel.visibility = View.GONE
            setStatus("Сохранено")
        }
        findViewById<Button>(R.id.btnTalk).setOnClickListener { startListening() }
        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val t = editText.text.toString().trim()
            if (t.isNotEmpty()) {
                editText.setText("")
                handleUserText(t)
            }
        }

        tts = TextToSpeech(this, this)
        addLog("Джарвис", "Готов. Нажмите микрофон и говорите.")
    }

    // ---------- Озвучка ----------
    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ru", "RU"))
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ttsReady) addLog("Система", "Нет русского голоса. Установите «Синтез речи Google» и русский голос в настройках.")
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ---------- Распознавание речи ----------
    private fun startListening() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            addLog("Система", "Распознавание речи недоступно. Нужно приложение Google.")
            return
        }
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = setStatus("Слушаю…")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() = setStatus("Распознаю…")
                override fun onError(error: Int) {
                    setStatus(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания (включите VPN)"
                            else -> "Ошибка распознавания ($error)"
                        }
                    )
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) setStatus("Не расслышал") else handleUserText(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == REQ_AUDIO) {
            addLog("Система", "Без доступа к микрофону я не слышу. Разрешите его в настройках.")
        }
    }

    // ---------- Мозг ----------
    private fun handleUserText(text: String) {
        addLog("Вы", text)
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) {
            val msg = "Сначала вставьте API-ключ: кнопка «Ключ» сверху."
            addLog("Джарвис", msg); speak(msg); return
        }
        busy = true
        setStatus("Думаю…")
        history.add(JSONObject().put("role", "user").put("content", text))
        while (history.size > 20) history.removeAt(0)

        Thread {
            val answer = try {
                askLlm(key)
            } catch (e: Exception) {
                history.removeAt(history.size - 1)
                "Ошибка связи: ${e.message}"
            }
            runOnUiThread {
                busy = false
                addLog("Джарвис", answer)
                setStatus("Нажмите и говорите")
                speak(answer)
            }
        }.start()
    }

    private fun askLlm(key: String): String {
        val url = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        val model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        synchronized(history) { history.forEach { messages.put(it) } }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 400)

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw Exception("код $code ${resp.take(200)}")

        val content = JSONObject(resp).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content").trim()
        history.add(JSONObject().put("role", "assistant").put("content", content))
        return content
    }

    // ---------- Интерфейс ----------
    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun addLog(who: String, text: String) {
        log.append("$who: $text\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
