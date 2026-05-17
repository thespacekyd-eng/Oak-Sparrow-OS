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
class WebSearchEngine(private val searchApiKey: String = "") {

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
                if (searchApiKey.isNotBlank()) {
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
        // DuckDuckGo HTML search (no API key needed)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            connectTimeout = 8000
            readTimeout = 15000
        }

        val html = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        // Parse DDG HTML results
        val results = mutableListOf<SearchResult>()
        val resultPattern = Regex("""class="result__a"[^>]*href="([^"]*)"[^>]*>([^<]*)</a>""")
        val snippetPattern = Regex("""class="result__snippet"[^>]*>([^<]*)<""")
        val titles = resultPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in 0 until minOf(titles.size, snippets.size, maxResults)) {
            val rawUrl = titles[i].groupValues[1]
            // DDG wraps URLs in a redirect — extract the actual URL
            val actualUrl = if (rawUrl.contains("uddg=")) {
                URLDecoder.decode(
                    Regex("uddg=([^&]+)").find(rawUrl)?.groupValues?.get(1) ?: rawUrl,
                    "UTF-8"
                )
            } else rawUrl
            results.add(SearchResult(
                title = titles[i].groupValues[2].trim(),
                url = actualUrl,
                snippet = snippets[i].groupValues[1].trim(),
            ))
        }

        val summary = if (results.isEmpty()) {
            "No results found for: $query"
        } else {
            results.joinToString("\n\n") { r ->
                "${r.title}\n${r.snippet}\nSource: ${r.url}"
            }
        }
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
