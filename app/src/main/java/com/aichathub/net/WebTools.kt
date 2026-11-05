package com.aichathub.net

import com.aichathub.data.SearchConfig
import com.aichathub.data.SearchEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 內建工具：只使用官方授權的搜尋 API（Tavily、Brave Search）或使用者自架的 SearXNG，
 * 不爬取 Google 等搜尋引擎結果頁，避免違反服務條款。
 */
class WebTools(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    data class Result(val title: String, val url: String, val snippet: String)

    fun search(cfg: SearchConfig, key: String?, query: String, count: Int = 6): String {
        val results = when (cfg.engine) {
            SearchEngine.NONE -> return "尚未設定搜尋引擎。請使用者到「工具設定」設定 Tavily / Brave API Key 或 SearXNG 網址。"
            SearchEngine.TAVILY -> tavily(key ?: throw IOException("缺少 Tavily API Key"), query, count)
            SearchEngine.BRAVE -> brave(key ?: throw IOException("缺少 Brave API Key"), query, count)
            SearchEngine.SEARXNG -> searx(cfg.searxUrl, query, count)
        }
        if (results.isEmpty()) return "沒有找到「$query」的結果。"
        return results.mapIndexed { i, r -> "${i + 1}. ${r.title}\n${r.url}\n${r.snippet.take(500)}" }.joinToString("\n\n")
    }

    private fun getJson(req: Request): JsonObject = http.newCall(req).execute().use { r ->
        val b = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw IOException("搜尋 HTTP ${r.code}：${b.take(200)}")
        json.parseToJsonElement(b).jsonObject
    }

    private fun tavily(key: String, q: String, n: Int): List<Result> {
        val body = buildJsonObject { put("query", q); put("max_results", n) }.toString()
        val o = getJson(Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType())).build())
        return (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            Result(r.s("title").orEmpty(), r.s("url").orEmpty(), r.s("content").orEmpty())
        }
    }

    private fun brave(key: String, q: String, n: Int): List<Result> {
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", q).addQueryParameter("count", n.toString()).build()
        val o = getJson(Request.Builder().url(url).header("X-Subscription-Token", key).header("Accept", "application/json").build())
        val web = o["web"] as? JsonObject
        return (web?.get("results") as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            Result(r.s("title").orEmpty(), r.s("url").orEmpty(), stripTags(r.s("description").orEmpty()))
        }
    }

    private fun searx(base: String, q: String, n: Int): List<Result> {
        val url = "${base.trim().trimEnd('/')}/search".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", q)?.addQueryParameter("format", "json")?.build()
            ?: throw IOException("SearXNG 網址無效")
        val o = getJson(Request.Builder().url(url).header("Accept", "application/json").build())
        return (o["results"] as? JsonArray).orEmpty().take(n).mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            Result(r.s("title").orEmpty(), r.s("url").orEmpty(), r.s("content").orEmpty())
        }
    }

    /** 讀取網頁並轉成純文字 */
    fun fetch(url: String, maxChars: Int = 15000): String {
        val u = url.trim().toHttpUrlOrNull() ?: throw IOException("網址無效：$url")
        val req = Request.Builder().url(u)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) AIChatHub/1.0 (+agent fetch)")
            .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5")
            .build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val type = r.header("Content-Type").orEmpty()
            val raw = r.peekBody(3_000_000).string()
            val text = if (type.contains("html") || raw.trimStart().startsWith("<")) htmlToText(raw) else raw
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            buildString {
                if (title.isNotEmpty()) append("標題：").append(decode(title)).append('\n')
                append("網址：").append(u).append("\n\n")
                append(text.take(maxChars))
                if (text.length > maxChars) append("\n…（內容已截斷）")
            }
        }
    }

    fun now(): String = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE zzz"))

    companion object {
        fun stripTags(s: String) = decode(s.replace(Regex("<[^>]+>"), ""))

        fun decode(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

        fun htmlToText(html: String): String {
            var s = html.replace(Regex("(?is)<(script|style|noscript|svg|template|head|nav|footer)\\b[^>]*>.*?</\\1>"), " ")
            s = s.replace(Regex("(?is)<!--.*?-->"), " ")
            s = s.replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|section|article|pre|blockquote)>"), "\n")
            s = s.replace(Regex("(?i)<li[^>]*>"), "\n• ")
            s = s.replace(Regex("<[^>]+>"), " ")
            s = decode(s)
            s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            s = s.replace(Regex("\\n\\s*\\n+"), "\n\n")
            return s.lines().joinToString("\n") { it.trim() }.trim()
        }
    }
}
