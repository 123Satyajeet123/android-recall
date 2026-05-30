package com.brave.veloxcore.query

  import android.util.Log
  import androidx.sqlite.db.SimpleSQLiteQuery
  import com.brave.veloxcore.data.CaptureDao
  import java.text.SimpleDateFormat
  import java.util.Date
  import java.util.Locale

  class QueryEngine(
      private val dao: CaptureDao,
      private val generate: (String) -> String
  ) {

      companion object {
          private const val TAG = "VeloxQuery"
      }

      fun query(question: String): String {
          // Stage 1: Extract search keyword
          val extractPrompt = Prompts.extractKeyword(question)
          val keyword = generate(extractPrompt).trim().lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
          Log.d(TAG, "Extracted keyword: '$keyword'")

          if (keyword.isEmpty()) return "I couldn't understand what to search for."

          // Retrieval: FTS4 search (light stemming + prefix match)
          val searchTerm = keyword.split(" ").joinToString(" ") { word ->
              val stem = when {
                  word.endsWith("ing") && word.length > 6 -> word.removeSuffix("ing")
                  word.endsWith("ed") && word.length > 5 -> word.removeSuffix("ed")
                  word.endsWith("s") && !word.endsWith("ss") && word.length > 3 -> word.removeSuffix("s")
                  else -> word
              }
              "$stem*"
          }
          Log.d(TAG, "Search term: '$searchTerm'")
          val ftsQuery = SimpleSQLiteQuery(
              "SELECT c.* FROM captures c JOIN captures_fts fts ON c.id = fts.docid WHERE captures_fts MATCH ? ORDER BY c.timestamp DESC LIMIT 5",
              arrayOf<Any>(searchTerm)
          )
          var results = dao.searchFts(ftsQuery)
          Log.d(TAG, "Found ${results.size} results for '$searchTerm'")

          // Fallback: if multi-word search fails, try first word only
          if (results.isEmpty() && keyword.contains(" ")) {
              val firstWord = keyword.split(" ").first()
              val fallbackStem = when {
                  firstWord.endsWith("ing") && firstWord.length > 6 -> firstWord.removeSuffix("ing")
                  firstWord.endsWith("ed") && firstWord.length > 5 -> firstWord.removeSuffix("ed")
                  firstWord.endsWith("s") && !firstWord.endsWith("ss") && firstWord.length > 3 -> firstWord.removeSuffix("s")
                  else -> firstWord
              }
              val fallbackQuery = SimpleSQLiteQuery(
                  "SELECT c.* FROM captures c JOIN captures_fts fts ON c.id = fts.docid WHERE captures_fts MATCH ? ORDER BY c.timestamp DESC LIMIT 5",
                  arrayOf<Any>("$fallbackStem*")
              )
              results = dao.searchFts(fallbackQuery)
              Log.d(TAG, "Fallback '$fallbackStem*': ${results.size} results")
          }

          if (results.isEmpty()) return "I haven't seen '$keyword' on your screen yet."

          // Format answer in code (1B model is too small for reliable Stage 2)
          val latest = results.first()
          val time = formatTime(latest.timestamp)
          val app = latest.packageName.substringAfterLast(".")
          val answer = "You saw '$keyword' in $app, $time. Found in ${results.size} captures total."
          Log.d(TAG, "Answer: $answer")

          return answer
      }

      private fun formatTime(timestamp: Long): String {
          val now = System.currentTimeMillis()
          val diff = now - timestamp
          return when {
              diff < 60_000 -> "just now"
              diff < 3_600_000 -> "${diff / 60_000} min ago"
              diff < 86_400_000 -> {
                  val h = diff / 3_600_000
                  if (h == 1L) "1 hour ago" else "$h hours ago"
              }
              else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
          }
      }
  }
