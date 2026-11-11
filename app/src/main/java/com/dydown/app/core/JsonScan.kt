package com.dydown.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 抖音页面里的 JSON 是"夹在 JS 里的一大坨"，而且层级经常改版。
 * 这里只做两件事：从任意位置抠出一段完整 JSON、按 key 递归找值。
 * 不写死 any 具体路径 —— 路径写死就是给自己埋雷。
 */
object JsonScan {

    /** 找到 marker（比如 window._ROUTER_DATA = ）后面第一个完整 JSON 对象 */
    fun extractAfter(src: String, marker: String): JSONObject? {
        var from = 0
        while (true) {
            val idx = src.indexOf(marker, from)
            if (idx < 0) return null
            val brace = src.indexOf('{', idx + marker.length)
            if (brace < 0) return null
            val end = matchBrace(src, brace)
            if (end > brace) {
                val text = src.substring(brace, end)
                runCatching { JSONObject(text) }.getOrNull()?.let { return it }
            }
            from = idx + marker.length
        }
    }

    /** 从 start（必须指向 '{'）扫描到配对的 '}'，正确处理字符串里的花括号和转义 */
    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var i = start
        var inStr = false
        var esc = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return -1
    }

    /** 递归找第一个 size >= minSize 的数组 */
    fun findArray(node: Any?, key: String, minSize: Int = 1): JSONArray? {
        when (node) {
            is JSONObject -> {
                val direct = node.optJSONArray(key)
                if (direct != null && direct.length() >= minSize) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    findArray(node.opt(keys.next()), key, minSize)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findArray(node.opt(i), key, minSize)?.let { return it }
                }
            }
        }
        return null
    }

    /** 递归找第一个非空字符串，支持多个候选 key（兼容 snake_case / camelCase） */
    fun findString(node: Any?, vararg keys: String): String? {
        when (node) {
            is JSONObject -> {
                for (k in keys) {
                    val v = node.optString(k)
                    if (v.isNotEmpty() && v != "null") return v
                }
                val it = node.keys()
                while (it.hasNext()) {
                    findString(node.opt(it.next()), *keys)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findString(node.opt(i), *keys)?.let { return it }
                }
            }
        }
        return null
    }

    /** 按多个候选 key 取字符串 */
    fun str(o: JSONObject?, vararg keys: String): String? {
        if (o == null) return null
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }

    fun obj(o: JSONObject?, vararg keys: String): JSONObject? {
        if (o == null) return null
        for (k in keys) o.optJSONObject(k)?.let { return it }
        return null
    }

    fun arr(o: JSONObject?, vararg keys: String): JSONArray? {
        if (o == null) return null
        for (k in keys) o.optJSONArray(k)?.let { return it }
        return null
    }

    /** url_list 的第一项 */
    fun firstUrl(o: JSONObject?): String? {
        val list = arr(o, "url_list", "urlList") ?: return null
        for (i in 0 until list.length()) {
            val u = list.optString(i, "")
            if (u.startsWith("http")) return u
        }
        return null
    }

    /** 抖音 CDN 的无水印 / 高清修正 */
    fun normalize(url: String): String {
        var u = url.trim()
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        u = u.replace("/playwm/", "/play/")
        u = u.replace("ratio=720p", "ratio=1080p")
        return u
    }
}
