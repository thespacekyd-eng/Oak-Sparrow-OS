package dev.governance.android.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Inline web search and URL fetching for Oak.
 *
 * Two modes:
 * 1. [search] — queries a search API and returns snippets with sources
 * 2. [fetchUrl] — downloads a web page, strips HTML, returns clean text
 *
 * Results are fed back to the ConversationEngine so Oak can answer
 * questions with live data and cite sources — like ChatGPT with browsing.
 *
 * Uses Brave Search API (free tier: 2000 queries/month) for web search.
 * Falls back to a simple Google scrape if no API key is configured.
 */
class WebSearchEngine(
    private val searchApiKey: String = "",
    private val serpApiKey: String = "",
) {

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    data class SearchResponse(
        val results: List<SearchResult>,
        val summary: String,
    )

    /**
     * Searches the web and returns structured results with snippets.
     */
    suspend fun search(query: String, maxResults: Int = 5): SearchResponse =
        withContext(Dispatchers.IO) {
            try {
                if (serpApiKey.isNotBlank()) {
                    searchSerpApi(query, maxResults)
                } else if (searchApiKey.isNotBlank()) {
                    searchBrave(query, maxResults)
                } else {
                    // Fallback: use DuckDuckGo instant answer API (no key needed)
                    searchDuckDuckGo(query, maxResults)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Web search failed: ${e.message}", e)
                SearchResponse(emptyList(), "Search failed: ${e.message}")
            }
        }

    /**
     * Fetches a URL and returns the text content (HTML stripped).
     * Useful for "summarize this article" commands.
     */
    suspend fun fetchUrl(url: String, maxChars: Int = 8000): String =
        withContext(Dispatchers.IO) {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    connectTimeout = 8000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                }
                val html = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                stripHtml(html).take(maxChars)
            } catch (e: Exception) {
                Log.e(TAG, "URL fetch failed: ${e.message}", e)
                "Could not fetch URL: ${e.message}"
            }
        }

    private fun searchSerpApi(query: String, maxResults: Int): SearchResponse {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://serpapi.com/search.json?q=$encoded&api_key=$serpApiKey&engine=google&num=$maxResults"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8000
            readTimeout = 15000
        }

        val responseStr = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = Json.parseToJsonElement(responseStr).jsonObject

        val results = mutableListOf<SearchResult>()

        // Answer box (direct answer from Google)
        val answerBox = json["answer_box"]?.jsonObject
        if (answerBox != null) {
            val answer = answerBox["answer"]?.jsonPrimitive?.content
                ?: answerBox["snippet"]?.jsonPrimitive?.content
                ?: answerBox["result"]?.jsonPrimitive?.content
            if (answer != null) {
                results.add(SearchResult(
                    title = answerBox["title"]?.jsonPrimitive?.content ?: "Answer",
                    url = answerBox["link"]?.jsonPrimitive?.content ?: "",
                    snippet = answer,
                ))
            }
        }

        // Knowledge graph
        val kg = json["knowledge_graph"]?.jsonObject
        if (kg != null) {
            val desc = kg["description"]?.jsonPrimitive?.content
            if (desc != null) {
                results.add(SearchResult(
                    title = kg["title"]?.jsonPrimitive?.content ?: "Knowledge",
                    url = kg["source"]?.jsonObject?.get("link")?.jsonPrimitive?.content ?: "",
                    snippet = desc,
                ))
            }
        }

        // Organic results
        val organic = json["organic_results"]?.jsonArray ?: JsonArray(emptyList())
        for (item in organic.take(maxResults)) {
            val obj = item.jsonObject
            results.add(SearchResult(
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                url = obj["link"]?.jsonPrimitive?.content ?: "",
                snippet = obj["snippet"]?.jsonPrimitive?.content ?: "",
            ))
        }

        Log.i(TAG, "SerpAPI: ${results.size} results for '$query'")
        return buildResponse(results.take(maxResults), query)
    }

    private fun searchBrave(query: String, maxResults: Int): SearchResponse {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$maxResults"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Subscription-Token", searchApiKey)
            connectTimeout = 8000
            readTimeout = 15000
        }

        val responseStr = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        val json = Json.parseToJsonElement(responseStr).jsonObject
        val webResults = json["web"]?.jsonObject?.get("results")?.jsonArray ?: JsonArray(emptyList())

        val results = webResults.take(maxResults).map { item ->
            val obj = item.jsonObject
            SearchResult(
                title = obj["title"]?.jsonPrimitive?.content ?: "",
                url = obj["url"]?.jsonPrimitive?.content ?: "",
                snippet = obj["description"]?.jsonPrimitive?.content ?: "",
            )
        }

        val summary = results.joinToString("\n\n") { r ->
            "${r.title}\n${r.snippet}\nSource: ${r.url}"
        }
        return SearchResponse(results, summary)
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): SearchResponse {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // Strategy 1: SearXNG public instances (JSON API, no CAPTCHA)
        val searxInstances = listOf(
            "https://search.sapti.me",
            "https://searx.be",
            "https://search.bus-hit.me",
        )
        for (instance in searxInstances) {
            try {
                val url = "$instance/search?q=$encoded&format=json&categories=general&language=en"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "OakAssistant/1.0")
                    connectTimeout = 5000
                    readTimeout = 10000
                }
                val code = connection.responseCode
                if (code != 200) { connection.disconnect(); continue }
                val jsonStr = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                val resultsArr = json["results"]?.jsonArray ?: continue

                val results = resultsArr.take(maxResults).map { item ->
                    val obj = item.jsonObject
                    SearchResult(
                        title = obj["title"]?.jsonPrimitive?.content ?: "",
                        url = obj["url"]?.jsonPrimitive?.content ?: "",
                        snippet = obj["content"]?.jsonPrimitive?.content ?: "",
                    )
                }.filter { it.title.isNotBlank() }

                if (results.isNotEmpty()) {
                    Log.i(TAG, "SearXNG ($instance): ${results.size} results for '$query'")
                    return buildResponse(results, query)
                }
            } catch (e: Exception) {
                Log.d(TAG, "SearXNG $instance failed: ${e.message}")
            }
        }

        // Strategy 2: DDG instant answer API (good for factual queries)
        try {
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OakAssistant/1.0")
                connectTimeout = 5000
                readTimeout = 10000
            }
            val jsonStr = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            val abstract = json["AbstractText"]?.jsonPrimitive?.content ?: ""
            val abstractSource = json["AbstractSource"]?.jsonPrimitive?.content ?: ""
            val abstractUrl = json["AbstractURL"]?.jsonPrimitive?.content ?: ""

            if (abstract.isNotBlank()) {
                Log.i(TAG, "DDG instant answer: ${abstract.length} chars from $abstractSource")
                return SearchResponse(
                    results = listOf(SearchResult(abstractSource, abstractUrl, abstract)),
                    summary = "$abstract\nSource: $abstractSource ($abstractUrl)",
                )
            }

            val topics = json["RelatedTopics"]?.jsonArray ?: JsonArray(emptyList())
            val results = topics.take(maxResults).mapNotNull { topic ->
                try {
                    val obj = topic.jsonObject
                    val text = obj["Text"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val firstUrl = obj["FirstURL"]?.jsonPrimitive?.content ?: ""
                    SearchResult(text.take(80), firstUrl, text)
                } catch (_: Exception) { null }
            }
            if (results.isNotEmpty()) return buildResponse(results, query)
        } catch (e: Exception) {
            Log.w(TAG, "DDG API failed: ${e.message}")
        }

        Log.w(TAG, "All search strategies failed for '$query'")
        return SearchResponse(emptyList(), "No search results found for: $query")
    }

    private fun buildResponse(results: List<SearchResult>, query: String): SearchResponse {
        val summary = results.joinToString("\n\n") { r ->
            "${r.title}\n${r.snippet}\nSource: ${r.url}"
        }
        Log.i(TAG, "Search '$query': ${results.size} results")
        return SearchResponse(results, summary)
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<nav[^>]*>[\\s\\S]*?</nav>"), "")
            .replace(Regex("<header[^>]*>[\\s\\S]*?</header>"), "")
            .replace(Regex("<footer[^>]*>[\\s\\S]*?</footer>"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#\\d+;"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    companion object {
        private const val TAG = "OakWebSearch"
    }
}

private object URLDecoder {
    fun decode(s: String, charset: String): String = java.net.URLDecoder.decode(s, charset)
}
