package com.brave.veloxcore.query

object Prompts {

    fun extractKeyword(question: String): String {
        val q = question.lowercase().trim()

        // Strip common question prefixes
        val prefixes =
                listOf(
                        "where did i see ",
                        "when did i see ",
                        "what about ",
                        "show me ",
                        "find ",
                        "search for ",
                        "where is ",
                        "what was ",
                        "did i see ",
                        "have i seen ",
                )

        for (prefix in prefixes) {
            if (q.startsWith(prefix)) {
                return q.removePrefix(prefix).replace(Regex("[?.!,]"), "").trim()
            }
        }

        // Fallback: remove stop words and use what's left
        val stopWords =
                setOf(
                        "the",
                        "a",
                        "an",
                        "is",
                        "was",
                        "on",
                        "my",
                        "screen",
                        "today",
                        "yesterday",
                        "i",
                        "did",
                        "do",
                        "what",
                        "where",
                        "when",
                        "how"
                )
        val words =
                q.replace(Regex("[?.!,]"), "").split(" ").filter {
                    it.length > 2 && it !in stopWords
                }

        return words.lastOrNull() ?: ""
    }
    
    fun formatAnswer(question: String, results: String): String =
            """
        User question: $question
        Data:
        $results
        Write one sentence answering the question using the data above.
        Response:""".trimIndent()
}
