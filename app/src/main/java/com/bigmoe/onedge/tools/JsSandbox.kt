package com.bigmoe.onedge.tools

import org.json.JSONObject
import org.json.JSONTokener

/** Wrapping and result handling for run_javascript, separated from the WebView so it is unit-testable. */
object JsSandbox {

    /**
     * A self-contained expression that runs [code] with a captured `console`, and evaluates to a JSON string
     * `{"logs":[...],"value":"..."|null,"error":"..."|null}`. Direct eval, so `console` is the captured one.
     */
    fun wrap(code: String): String = """
        (function(){
          var __out = [];
          var __fmt = function(v){ try { if (typeof v === 'string') return v; if (v === undefined) return 'undefined'; return JSON.stringify(v); } catch (e) { return String(v); } };
          var console = {};
          ['log','info','warn','error','debug'].forEach(function(k){ console[k] = function(){ __out.push((k === 'log' || k === 'info' ? '' : k + ': ') + Array.prototype.map.call(arguments, __fmt).join(' ')); }; });
          var __r, __e = null;
          try { __r = eval(${JSONObject.quote(code)}); } catch (e) { __e = String((e && e.stack) || e); }
          return JSON.stringify({ logs: __out, value: (__r === undefined ? null : __fmt(__r)), error: __e });
        })()
    """.trimIndent()

    /** [raw] is what WebView.evaluateJavascript hands back (a JSON-encoded string), or null on timeout. */
    fun format(raw: String?, timedOut: Boolean): String {
        if (timedOut || raw == null) return "Error: the code did not finish within the time limit and was stopped."
        val inner = try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else raw
        } catch (_: Exception) { raw }
        val obj = try { JSONObject(inner) } catch (_: Exception) { return "Error: unexpected result from the sandbox: ${raw.take(200)}" }
        val sb = StringBuilder()
        val logs = obj.optJSONArray("logs")
        if (logs != null) for (i in 0 until logs.length()) sb.append(logs.optString(i)).append('\n')
        if (!obj.isNull("error")) sb.append("Error: ").append(obj.optString("error")).append('\n')
        else if (!obj.isNull("value")) sb.append("=> ").append(obj.optString("value")).append('\n')
        val text = sb.toString().trimEnd()
        return if (text.isEmpty()) "(the code ran and printed nothing)" else text
    }
}
