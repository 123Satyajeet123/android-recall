package com.brave.veloxcore

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.brave.veloxcore.data.CaptureEntity
import com.brave.veloxcore.data.RecallDatabase
import com.brave.veloxcore.query.QueryEngine
import com.google.ai.edge.litertlm.*
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch

private val BLOCKED_PACKAGES = setOf(
      "com.google.android.apps.authenticator2",
      "com.authy.authy",
      "org.thoughtcrime.securesms",       // Signal
      "com.phonepe.app",
      "com.paytm",
      "com.google.android.apps.walletnfcrel", // Google Pay
      "in.org.npci.upiapp",               // BHIM
      "com.csam.icici.bank.imobile",
      "com.sbi.SBIFreedomPlus",
      "net.one97.paytm",
      "com.application.zomato",           // has payment
  )


class RecallAccessibilityService : AccessibilityService() {

    private var paused = false

  companion object {
          private const val TAG = "VeloxRecall"
          private const val MIN_INTERVAL_MS = 2000L
          private const val MODEL_PATH = "/data/local/tmp/gemma3-1b-generic.litertlm"
      }

      private lateinit var db: RecallDatabase
      private val executor = Executors.newSingleThreadExecutor()
      private var llmEngine: Engine? = null
      private var llmConversation: Conversation? = null

      private var lastHash: String = ""
      private var lastStoreTime: Long = 0

  override fun onServiceConnected() {

    val filter = IntentFilter().apply {
      addAction("com.brave.veloxcore.PAUSE")
      addAction("com.brave.veloxcore.RESUME")
      addAction("com.brave.veloxcore.QUERY")
  }
    registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
          when (intent?.action) {
              "com.brave.veloxcore.PAUSE" -> {
                  paused = true
                  Log.i(TAG, "PAUSED")
              }
              "com.brave.veloxcore.RESUME" -> {
                  paused = false
                  Log.i(TAG, "RESUMED")
              }
              "com.brave.veloxcore.QUERY" -> {
                  val question = intent.getStringExtra("q") ?: return
                  Log.i(TAG, "Query: $question")
                  executor.execute {
                      val engine = getOrCreateLlm()
                      if (engine == null) {
                          Log.e(TAG, "LLM failed to initialize")
                          return@execute
                      }
                      val queryEngine = QueryEngine(db.captureDao()) { prompt ->
                          generateText(prompt)
                      }
                      val answer = queryEngine.query(question)
                      Log.i(TAG, "Answer: $answer")
                  }
              }
          }
      }
  }, filter, RECEIVER_NOT_EXPORTED)

    
      db = RecallDatabase.getInstance(this)
      Log.i(TAG, "Recall service connected — watching screen")

      executor.execute {
          getOrCreateLlm()
      }
  }

  // called when anything changes on the screen
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
          if (paused) return

          if (event == null) return

          val packageName = event.packageName?.toString() ?: "unknown"
          if (packageName in BLOCKED_PACKAGES) return

          // What type of change happened?
          val eventType = when (event.eventType) {
              AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "APP_SWITCH"
              AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
              AccessibilityEvent.TYPE_VIEW_SCROLLED -> "SCROLLED"
              else -> return  // ignore other events
          }

          // Get the root node (top of the UI tree)
          val rootNode = rootInActiveWindow ?: return

          // Extract all text from the tree
          val screenText = StringBuilder()
          extractText(rootNode, screenText)
          val text = screenText.toString().trim()

          if (text.isEmpty()) return

          // DEDUPLICATION: hash the text
          val hash = sha256(text)

          // Skip if same content as last capture
          if (hash == lastHash) return

          // THROTTLE: skip if less than 2 seconds since last store
          val now = System.currentTimeMillis()
          if (now - lastStoreTime < MIN_INTERVAL_MS && eventType != "APP_SWITCH") return

          lastHash = hash
          lastStoreTime = now

          executor.execute {
              // Double-check: don't store if hash already exists in DB
              if (!db.captureDao().existsByHash(hash)) {
                  val capture = CaptureEntity(
                      timestamp = now,
                      packageName = packageName,
                      eventType = eventType,
                      textContent = text.take(5000),  // cap at 5000 chars per capture
                      contentHash = hash
                  )
                  
                  val rowId = db.captureDao().insert(capture)
                  db.openHelper.writableDatabase.execSQL(
                      "INSERT INTO captures_fts(docid, textContent) VALUES (?, ?)",
                      arrayOf<Any>(rowId, text.take(5000))
                  )

                  val count = db.captureDao().getCount()
                  Log.d(TAG, "Stored [$eventType] $packageName (${text.length} chars, total: $count)")
              }
            }
  }

  // Recursively walk the UI tree and collect all text
  private fun extractText(node: AccessibilityNodeInfo, sb: StringBuilder) {
          // Get this node's text
          node.text?.let { text ->
              if (text.isNotBlank()) {
                  sb.append(text).append(" ")
              }
          }


          // Also check content description (for images with alt text)
          node.contentDescription?.let { desc ->
              if (desc.isNotBlank()) {
                  sb.append(desc).append(" ")
              }
          }

          // Visit all children
          for (i in 0 until node.childCount) {
              val child = node.getChild(i) ?: continue
              extractText(child, sb)
              child.recycle()
          }
  }

  private fun sha256(input: String): String {
          val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
          return bytes.joinToString("") { "%02x".format(it) }
      }


  private fun getOrCreateLlm(): Engine? {
      if (llmEngine != null) return llmEngine
      return try {
          val config = EngineConfig(
              modelPath = MODEL_PATH,
              backend = Backend.CPU(),
              maxNumTokens = 512,
          )
          llmEngine = Engine(config).also { it.initialize() }
          llmConversation = llmEngine!!.createConversation(ConversationConfig())
          Log.i(TAG, "LLM initialized")
          llmEngine
      } catch (e: Exception) {
          Log.e(TAG, "LLM init failed: ${e.message}")
          null
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
              result.set("Error: ${error.message}")
              latch.countDown()
          }
      })
      latch.await()
      return result.get()
  }

  override fun onInterrupt() {
      Log.w(TAG, "Service interrupted")
  }

  override fun onDestroy() {
          super.onDestroy()
          llmConversation = null
          llmEngine?.close()
          llmEngine = null
          executor.shutdown()
      }
}