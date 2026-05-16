package io.itsikh.finnencer.data.providers

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Minimal RSS 2.0 + Atom 1.0 parser tuned for the publishers finnencer talks
 * to (Nasdaq, Seeking Alpha, SEC EDGAR). Tolerant of mixed namespaces and
 * missing optional fields.
 */
object FeedParser {

    data class FeedItem(
        val guid: String,
        val title: String,
        val link: String,
        val publishedAtMillis: Long,
        val summary: String?,
        val source: String?,
    )

    fun parse(xml: String): List<FeedItem> {
        if (xml.isBlank()) return emptyList()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val items = mutableListOf<FeedItem>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item" -> readRssItem(parser)?.let(items::add)
                    "entry" -> readAtomEntry(parser)?.let(items::add)
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun readRssItem(parser: XmlPullParser): FeedItem? {
        var guid: String? = null
        var title: String? = null
        var link: String? = null
        var pubDate: String? = null
        var description: String? = null
        var source: String? = null

        // Parser enters here positioned AT the <item> START_TAG. Step past it
        // so the inner loop starts on the first child, not on the <item> tag
        // itself (otherwise the else→skip branch would consume the whole
        // <item> without extracting any fields — that was the v0.0.x bug
        // where every RSS feed silently returned 0 items).
        parser.next()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name.equals("item", true))) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "guid" -> guid = readText(parser)
                    "title" -> title = readText(parser)
                    "link" -> link = readText(parser)
                    "pubdate" -> pubDate = readText(parser)
                    "description" -> description = readText(parser)
                    "source" -> source = readText(parser)
                    else -> skip(parser)
                }
            }
            parser.next()
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
        }

        val resolvedTitle = title?.trim().orEmpty()
        val resolvedLink = link?.trim().orEmpty()
        if (resolvedTitle.isBlank() || resolvedLink.isBlank()) return null
        return FeedItem(
            guid = guid?.trim()?.takeIf { it.isNotBlank() } ?: resolvedLink,
            title = resolvedTitle,
            link = resolvedLink,
            publishedAtMillis = parseRfc822(pubDate) ?: System.currentTimeMillis(),
            summary = description?.trim()?.takeIf { it.isNotBlank() && it != resolvedTitle },
            source = source?.trim(),
        )
    }

    private fun readAtomEntry(parser: XmlPullParser): FeedItem? {
        var id: String? = null
        var title: String? = null
        var link: String? = null
        var updated: String? = null
        var published: String? = null
        var summary: String? = null

        // Same fix as readRssItem — step past the <entry> START_TAG.
        parser.next()

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name.equals("entry", true))) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "id" -> id = readText(parser)
                    "title" -> title = readText(parser)
                    "link" -> {
                        // Atom <link href="..." rel="alternate"/>
                        val href = parser.getAttributeValue(null, "href")
                        if (link == null && !href.isNullOrBlank()) link = href
                        skip(parser) // close <link/>
                    }
                    "updated" -> updated = readText(parser)
                    "published" -> published = readText(parser)
                    "summary", "content" -> if (summary == null) summary = readText(parser)
                    else -> skip(parser)
                }
            }
            parser.next()
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
        }

        val resolvedTitle = title?.trim().orEmpty()
        val resolvedLink = link?.trim().orEmpty()
        if (resolvedTitle.isBlank() || resolvedLink.isBlank()) return null
        return FeedItem(
            guid = id?.trim()?.takeIf { it.isNotBlank() } ?: resolvedLink,
            title = resolvedTitle,
            link = resolvedLink,
            publishedAtMillis = parseIso8601(published)
                ?: parseIso8601(updated)
                ?: System.currentTimeMillis(),
            summary = summary?.trim()?.takeIf { it.isNotBlank() && it != resolvedTitle },
            source = null,
        )
    }

    private fun readText(parser: XmlPullParser): String? {
        if (parser.next() == XmlPullParser.TEXT) {
            val t = parser.text
            parser.nextTag()
            return t
        }
        return null
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private val RFC822: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss [Z][z]")

    private fun parseRfc822(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw.trim(), RFC822).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(raw.trim(), RFC822).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun parseIso8601(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
