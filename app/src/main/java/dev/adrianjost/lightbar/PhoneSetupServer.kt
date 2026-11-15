package dev.adrianjost.lightbar

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tiny web page served from the TV so the things that are painful to type with a remote
 * (Home Assistant URL, token, short light names) can be entered on a phone.
 */
class PhoneSetupServer(
    port: Int,
    private val currentUrl: String,
    private val hasToken: Boolean,
    private val lights: List<LightRef>,
    /** `token` is null when it was left empty to keep the stored one. */
    private val onReceived: (url: String, token: String?, names: Map<String, String>) -> Unit,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST) return page(form(""))
        runCatching { session.parseBody(HashMap()) }
        fun field(name: String) = session.parameters[name]?.firstOrNull().orEmpty().trim()

        val url = field("url")
        val token = field("token").filterNot { it.isWhitespace() }
        if (url.isEmpty()) return page(form("Please enter the Home Assistant address."))
        if (token.isEmpty() && !hasToken) return page(form("Please enter an access token."))

        val names = lights.mapIndexedNotNull { i, light ->
            field("name$i").takeIf { it.isNotEmpty() }?.let { light.id to it }
        }.toMap()
        onReceived(url, token.ifEmpty { null }, names)
        return page("<h1>Sent to your TV</h1><p>You can close this page.</p>")
    }

    private fun form(error: String): String {
        val names = if (lights.isEmpty()) "" else "<h2>Light names</h2>" +
            lights.withIndex().joinToString("") { (i, light) ->
                """<label>${escape(light.id)}</label><input name="name$i" value="${escape(light.name)}" maxlength="24">"""
            }
        val tokenHint = if (hasToken) "Leave empty to keep the current token" else "Profile → Security → Long-lived access tokens"
        return """
            <h1>LightBar setup</h1>
            <p class="err">$error</p>
            <form method="post">
              <label>Home Assistant address</label>
              <input name="url" value="${escape(currentUrl)}" placeholder="http://192.168.1.10:8123" autocapitalize="off">
              <label>Long-lived access token</label>
              <textarea name="token" rows="4" placeholder="$tokenHint"></textarea>
              $names
              <button>Send to TV</button>
            </form>
        """.trimIndent()
    }

    private fun page(body: String) = newFixedLengthResponse(
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>LightBar</title>
        <style>
          body{font-family:system-ui,sans-serif;background:#0b0e11;color:#fff;max-width:420px;margin:0 auto;padding:24px}
          h2{font-size:18px;margin:32px 0 0}
          label{display:block;margin:18px 0 6px;color:#aab4c0;font-size:14px}
          input,textarea{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:1px solid #2a323b;
            background:#151a20;color:#fff;font-size:16px}
          button{margin-top:28px;width:100%;padding:14px;border:0;border-radius:14px;background:#8ab8ff;
            color:#0e1216;font-size:16px;font-weight:600}
          .err{color:#ff9d9d;min-height:1em}
        </style></head><body>$body</body></html>
        """.trimIndent()
    )

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    companion object {
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress
        }.getOrNull()

        fun qrCode(content: String, size: Int = 480): Bitmap {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        }
    }
}
