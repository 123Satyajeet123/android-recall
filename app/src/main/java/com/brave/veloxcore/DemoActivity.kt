package com.brave.veloxcore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.brave.veloxcore.data.RecallDatabase
import kotlin.concurrent.thread

class DemoActivity : AppCompatActivity() {

    companion object {
        const val ACTION_VOICE_EVENT = "com.brave.veloxcore.VOICE_EVENT"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LATENCY = "latency"
    }

    private lateinit var statusText: TextView
    private lateinit var speakerText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var answerText: TextView
    private lateinit var latencyText: TextView
    private lateinit var historyText: TextView
    private lateinit var recallText: TextView

    private val history = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var recallPoller: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra(EXTRA_TYPE) ?: return
            val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
            val latency = intent.getStringExtra(EXTRA_LATENCY) ?: ""

            when (type) {
                "listening" -> {
                    statusText.text = "● Listening..."
                    statusText.setTextColor(0xFF3FB950.toInt())
                    speakerText.text = ""
                    transcriptText.text = ""
                    answerText.text = ""
                    latencyText.text = ""
                }
                "rejected" -> {
                    statusText.text = "✗ REJECTED"
                    statusText.setTextColor(0xFFF85149.toInt())
                    speakerText.text = "Speaker: UNKNOWN"
                    speakerText.setTextColor(0xFFF85149.toInt())
                    latencyText.text = "verify: ${latency}"
                    addHistory("✗ rejected (${latency})")
                    resetAfterDelay()
                }
                "verified" -> {
                    statusText.text = "✓ VERIFIED"
                    statusText.setTextColor(0xFF3FB950.toInt())
                    speakerText.text = "Speaker: owner ✓"
                    speakerText.setTextColor(0xFF3FB950.toInt())
                    latencyText.text = "verify: ${latency}"
                }
                "transcript" -> {
                    transcriptText.text = "\"$text\""
                    latencyText.text = latency
                }
                "answer" -> {
                    answerText.text = "→ $text"
                    latencyText.text = latency
                    addHistory("✓ \"${transcriptText.text}\" → ${text.take(40)}")
                    resetAfterDelay()
                }
                "enrolling" -> {
                    statusText.text = "◉ ENROLLING"
                    statusText.setTextColor(0xFFD29922.toInt())
                    speakerText.text = text
                    speakerText.setTextColor(0xFFD29922.toInt())
                }
            }
        }
    }

    private fun addHistory(line: String) {
        history.add(0, line)
        if (history.size > 8) history.removeAt(history.size - 1)
        historyText.text = history.joinToString("\n")
    }

    private fun resetAfterDelay() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            statusText.text = "● Listening..."
            statusText.setTextColor(0xFF3FB950.toInt())
            speakerText.text = ""
            transcriptText.text = ""
            answerText.text = ""
            latencyText.text = ""
            startRecallPoller()
        }, 8000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)

        statusText = findViewById(R.id.statusText)
        speakerText = findViewById(R.id.speakerText)
        transcriptText = findViewById(R.id.transcriptText)
        answerText = findViewById(R.id.answerText)
        latencyText = findViewById(R.id.latencyText)
        historyText = findViewById(R.id.historyText)

        registerReceiver(receiver, IntentFilter(ACTION_VOICE_EVENT), RECEIVER_NOT_EXPORTED)
        recallText = findViewById(R.id.recallText)

        // Start voice service if not running
        startForegroundService(Intent(this, VoiceService::class.java))

        // Poll recall DB every 2 seconds to show live captures
        startRecallPoller()
    }

    private fun startRecallPoller() {
        val db = RecallDatabase.getInstance(this)
        recallPoller = object : Runnable {
            override fun run() {
                thread {
                    val recent = db.captureDao().getRecent(5)
                    val text = recent.joinToString("\n") { capture ->
                        val app = capture.packageName.substringAfterLast(".")
                        val preview = capture.textContent.take(60).replace("\n", " ")
                        "[$app] $preview"
                    }
                    val count = db.captureDao().getCount()
                    handler.post {
                        recallText.text = "Total: $count captures\n$text"
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(recallPoller!!)
    }

    override fun onDestroy() {
        recallPoller?.let { handler.removeCallbacks(it) }
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
