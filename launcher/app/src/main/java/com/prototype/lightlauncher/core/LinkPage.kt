package com.prototype.lightlauncher.core

/**
 * The two pages the phone sees while it sends the album link to the TV (see LinkServer): a box to paste
 * the link into, and a thank-you. Plain HTML with its style inline, in English or Spanish by the phone's language.
 * Nothing the phone sent is ever written back into a page, so a page can carry nothing but this text.
 */
object LinkPage {

    /** What the browser is told it may do with the page: nothing but show it and send the form back to the TV. */
    const val CONTENT_SECURITY_POLICY: String = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'"

    /** True when the phone asks for Spanish first (an Accept-Language header such as "es-419,es;q=0.9,en;q=0.8"). */
    fun prefersSpanish(acceptLanguage: String?): Boolean =
        acceptLanguage.orEmpty().trim().lowercase().startsWith("es")

    /** The page with the box. [invalid] adds the line that says the last link was not a Google Photos album link. */
    fun form(spanish: Boolean, invalid: Boolean): String {
        val title: String = if (spanish) "Álbum para el protector de pantalla" else "Album for the screensaver"
        val help: String = if (spanish) {
            "En Google Fotos abrí el álbum compartido, tocá Compartir, copiá el enlace y pegalo acá."
        } else {
            "In Google Photos open the shared album, tap Share, copy the link and paste it here."
        }
        val error: String = if (!invalid) "" else {
            "<p class=\"error\">" + (if (spanish) "Ese no parece un enlace de un álbum de Google Fotos (photos.app.goo.gl o photos.google.com)." else "That does not look like a Google Photos album link (photos.app.goo.gl or photos.google.com).") + "</p>"
        }
        val button: String = if (spanish) "Enviar a la TV" else "Send to the TV"
        val label: String = if (spanish) "Enlace del álbum" else "Album link"
        return page(
            spanish,
            title,
            "<h1>$title</h1><p>$help</p>$error" +
                "<form method=\"post\"><label for=\"link\">$label</label>" +
                "<input id=\"link\" name=\"link\" type=\"url\" inputmode=\"url\" autocomplete=\"off\" autofocus required placeholder=\"https://photos.app.goo.gl/...\">" +
                "<button type=\"submit\">$button</button></form>",
        )
    }

    /** The page after the TV took the link. */
    fun done(spanish: Boolean): String {
        val title: String = if (spanish) "Listo" else "Done"
        val text: String = if (spanish) "La TV recibió el enlace y va a bajar las fotos. Ya podés cerrar esta página." else "The TV got the link and will download the photos. You can close this page."
        return page(spanish, title, "<h1>$title</h1><p>$text</p>")
    }

    private fun page(spanish: Boolean, title: String, body: String): String =
        "<!doctype html><html lang=\"${if (spanish) "es" else "en"}\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$title</title><style>" +
            "body{font-family:-apple-system,system-ui,sans-serif;margin:0;padding:24px;background:#f2f2f4;color:#1c1c1e}" +
            "h1{font-size:22px;margin:0 0 12px}p{font-size:16px;line-height:1.4}" +
            "label{display:block;font-size:14px;margin:18px 0 6px;color:#555}" +
            "input{width:100%;box-sizing:border-box;font-size:17px;padding:12px;border:1px solid #c7c7cc;border-radius:10px}" +
            "button{margin-top:16px;width:100%;font-size:17px;padding:14px;border:0;border-radius:10px;background:#1c1c1e;color:#fff}" +
            ".error{color:#b3261e}</style></head><body>$body</body></html>"
}
