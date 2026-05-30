package com.brave.veloxcore.query

object Prompts {

    fun extractKeyword(question: String): String = """
        Extract the ONE main search keyword from this question. Reply with ONLY that single word, nothing else.

        Question: "$question"
        Keyword:""".trimIndent()

    fun formatAnswer(question: String, results: String): String = """
        User question: $question
        Data:
        $results
        Write one sentence answering the question using the data above.
        Response:""".trimIndent()
}
