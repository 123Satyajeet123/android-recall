package com.brave.veloxcore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.brave.veloxcore.data.RecallDatabase
import com.brave.veloxcore.query.QueryEngine
import com.google.ai.edge.litertlm.*
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class VoiceService : Service() {

    companion object {
        private const val TAG = "VeloxVoice"
        private const val CHANNEL_ID = "velox_voice"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE  // 1 second minimum

        private const val MODEL_DIR = "/data/local/tmp/sherpa-models"

        private val HALLUCINATION_PATTERNS = listOf(
            "[BLANK", "[blank", "[Inaudible", "[inaudible",
            "(muffled", "(mumble", "(speaking in", "(music",
            "Thanks for watching", "Subscribe",
            "MBC뉴스", "www.", "♪",
        )
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    private var queryEngine: QueryEngine? = null
    private var llmEngine: Engine? = null
    private var llmConversation: Conversation? = null
    private var speakerExtractor: SpeakerEmbeddingExtractor? = null
    private var speakerManager: SpeakerEmbeddingManager? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var enrollMode = false
    private val enrollSamples = mutableListOf<FloatArray>()

    private val SPEAKER_THRESHOLD = 0.5f
    private val OWNER_NAME = "owner"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.brave.veloxcore.ENROLL" -> {
                        enrollMode = true
                        enrollSamples.clear()
                        Log.i(TAG, "ENROLLMENT MODE — speak 3 phrases")
                        updateNotification("Enrolling voice (0/3)...")
                    }
                }
            }
        }, IntentFilter("com.brave.veloxcore.ENROLL"), RECEIVER_NOT_EXPORTED)

        thread {
            val t0 = System.currentTimeMillis()
            initVad()
            initRecognizer()
            initSpeakerVerify()
            initQueryEngine()
            val initMs = System.currentTimeMillis() - t0
            Log.i(TAG, "All models initialized in ${initMs}ms")
            updateNotification(if (isOwnerEnrolled()) "Listening (verified)" else "No voice enrolled — send ENROLL")
            startListening()
        }
    }

    private fun initSpeakerVerify() {
        val config = SpeakerEmbeddingExtractorConfig(
            model = "$MODEL_DIR/campplus_en.onnx",
            numThreads = 1,
            provider = "cpu",
        )
        speakerExtractor = SpeakerEmbeddingExtractor(config = config)
        speakerManager = SpeakerEmbeddingManager(dim = speakerExtractor!!.dim())
        Log.i(TAG, "Speaker verify initialized (dim=${speakerExtractor!!.dim()})")

        loadEnrolledSpeaker()
    }

    private fun extractEmbedding(samples: FloatArray): FloatArray {
        val stream = speakerExtractor!!.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        val embedding = speakerExtractor!!.compute(stream)
        stream.release()
        return embedding
    }

    private fun verifySpeaker(samples: FloatArray): Boolean {
        if (!isOwnerEnrolled()) return true
        val embedding = extractEmbedding(samples)
        return speakerManager!!.verify(OWNER_NAME, embedding, SPEAKER_THRESHOLD)
    }

    private fun isOwnerEnrolled(): Boolean {
        return speakerManager?.contains(OWNER_NAME) == true
    }

    private fun handleEnrollment(samples: FloatArray) {
        val embedding = extractEmbedding(samples)
        enrollSamples.add(embedding)
        val count = enrollSamples.size
        Log.i(TAG, "Enrolled sample $count/3")
        updateNotification("Enrolling voice ($count/3)...")

        if (count >= 3) {
            speakerManager!!.add(OWNER_NAME, enrollSamples.toTypedArray())
            saveEnrolledSpeaker()
            enrollMode = false
            enrollSamples.clear()
            Log.i(TAG, "ENROLLMENT COMPLETE — voice registered")
            updateNotification("Listening (verified)")
        }
    }

    private fun saveEnrolledSpeaker() {
        val file = File(filesDir, "owner_embeddings.bin")
        val allFloats = enrollSamples.flatMap { it.toList() }
        file.writeBytes(FloatArray(allFloats.size) { allFloats[it] }.toByteArray())
        Log.i(TAG, "Saved ${enrollSamples.size} embeddings to ${file.absolutePath}")
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val bytes = ByteArray(size * 4)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return bytes
    }

    private fun loadEnrolledSpeaker() {
        val file = File(filesDir, "owner_embeddings.bin")
        if (!file.exists()) {
            Log.i(TAG, "No enrolled speaker found")
            return
        }
        val bytes = file.readBytes()
        val dim = speakerExtractor!!.dim()
        val numEmbeddings = bytes.size / (dim * 4)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        val embeddings = Array(numEmbeddings) { FloatArray(dim) { buf.float } }
        speakerManager!!.add(OWNER_NAME, embeddings)
        Log.i(TAG, "Loaded $numEmbeddings enrolled embeddings")
    }

    private fun initQueryEngine() {
        val db = RecallDatabase.getInstance(this)
        val captureCount = db.captureDao().getCount()
        Log.i(TAG, "Recall DB: $captureCount captures")

        try {
            val config = EngineConfig(
                modelPath = "$MODEL_DIR/../gemma3-1b-generic.litertlm",
                backend = Backend.CPU(),
                maxNumTokens = 512,
            )
            llmEngine = Engine(config).also { it.initialize() }
            llmConversation = llmEngine!!.createConversation(ConversationConfig())
            Log.i(TAG, "Gemma 1B initialized for query")
        } catch (e: Exception) {
            Log.w(TAG, "LLM init failed: ${e.message} — will use keyword-only search")
        }

        queryEngine = QueryEngine(db.captureDao()) { prompt ->
            generateText(prompt)
        }
    }

    private fun generateText(prompt: String): String {
        val conv = llmConversation ?: return ""
        val result = AtomicReference("")
        val latch = CountDownLatch(1)
        conv.sendMessageAsync(Contents.of(listOf(Content.Text(prompt))), object : MessageCallback {
            override fun onMessage(message: Message) {
                result.set(result.get() + message.toString())
            }
            override fun onDone() { latch.countDown() }
            override fun onError(error: Throwable) {
                Log.e(TAG, "LLM error: ${error.message}")
                result.set("")
                latch.countDown()
            }
        })
        latch.await()
        return result.get()
    }

    private fun initVad() {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$MODEL_DIR/silero_vad.onnx",
                threshold = 0.7f,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.8f,
                windowSize = 512,
                maxSpeechDuration = 15.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        )
        vad = Vad(config = config)
        Log.i(TAG, "VAD initialized (threshold=0.7, minSpeech=0.8s)")
    }

    private fun initRecognizer() {
        val whisperDir = "$MODEL_DIR/whisper-base.en"
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$whisperDir/base.en-encoder.int8.onnx",
                    decoder = "$whisperDir/base.en-decoder.int8.onnx",
                ),
                tokens = "$whisperDir/base.en-tokens.txt",
                modelType = "whisper",
                numThreads = 2,
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(config = config)
        Log.i(TAG, "Whisper base.en initialized")
    }

    private fun startListening() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        audioRecord!!.startRecording()
        isRunning = true
        Log.i(TAG, "Audio capture started (buffer: ${bufferSize * 2} bytes)")

        recordingThread = thread(true) {
            processAudioLoop()
        }
    }

    private fun processAudioLoop() {
        val buffer = ShortArray(512)
        var segmentCount = 0

        while (isRunning) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read <= 0) continue

            val samples = FloatArray(read) { buffer[it] / 32768.0f }
            vad?.acceptWaveform(samples)

            while (vad?.empty() == false) {
                val segment = vad!!.front()

                if (segment.samples.size < MIN_SEGMENT_SAMPLES) {
                    vad!!.pop()
                    continue
                }

                segmentCount++
                val durationMs = segment.samples.size * 1000 / SAMPLE_RATE

                if (enrollMode) {
                    sendUiEvent("enrolling", "Sample ${enrollSamples.size + 1}/3 — speak...")
                    handleEnrollment(segment.samples)
                    vad!!.pop()
                    continue
                }

                val verifyT0 = System.currentTimeMillis()
                val isOwner = verifySpeaker(segment.samples)
                val verifyMs = System.currentTimeMillis() - verifyT0

                if (!isOwner) {
                    Log.w(TAG, "[$segmentCount] REJECTED (${verifyMs}ms) — not owner voice")
                    sendUiEvent("rejected", "", "${verifyMs}ms")
                    vad!!.pop()
                    continue
                }

                sendUiEvent("verified", "", "${verifyMs}ms")

                val t0 = System.currentTimeMillis()
                val text = transcribe(segment.samples)
                val sttMs = System.currentTimeMillis() - t0

                if (text.isNotBlank() && !isHallucination(text)) {
                    Log.i(TAG, "[$segmentCount] VERIFIED (${verifyMs}ms) STT (${sttMs}ms, ${durationMs}ms audio) $text")
                    sendUiEvent("transcript", text, "verify: ${verifyMs}ms | stt: ${sttMs}ms")
                    handleQuery(text)
                } else if (text.isNotBlank()) {
                    Log.d(TAG, "Filtered: $text")
                    sendUiEvent("listening")
                }

                vad!!.pop()
            }
        }
    }

    private fun isHallucination(text: String): Boolean {
        return HALLUCINATION_PATTERNS.any { text.contains(it, ignoreCase = true) }
    }

    private fun handleQuery(transcript: String) {
        val engine = queryEngine ?: return
        val t0 = System.currentTimeMillis()
        val answer = engine.query(transcript)
        val queryMs = System.currentTimeMillis() - t0
        Log.i(TAG, "ANSWER (${queryMs}ms): $answer")
        sendUiEvent("answer", answer, "query: ${queryMs}ms")
    }

    private fun sendUiEvent(type: String, text: String = "", latency: String = "") {
        sendBroadcast(Intent(DemoActivity.ACTION_VOICE_EVENT).apply {
            setPackage(packageName)
            putExtra(DemoActivity.EXTRA_TYPE, type)
            putExtra(DemoActivity.EXTRA_TEXT, text)
            putExtra(DemoActivity.EXTRA_LATENCY, latency)
        })
    }

    private fun transcribe(samples: FloatArray): String {
        val rec = recognizer ?: return ""
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VeloxCore Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Always-on voice listening"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VeloxCore")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        isRunning = false
        recordingThread?.join(2000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        vad?.release()
        recognizer?.release()
        speakerExtractor?.release()
        llmConversation = null
        llmEngine?.close()
        llmEngine = null
        Log.i(TAG, "Voice service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
