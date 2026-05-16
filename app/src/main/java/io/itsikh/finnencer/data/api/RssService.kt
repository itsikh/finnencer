package io.itsikh.finnencer.data.api

import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Plain-XML fetcher for per-ticker RSS feeds. The Retrofit base URL is a
 * placeholder; every call supplies the full URL via [Url].
 *
 * Per-ticker feeds known to be useful:
 *  - Nasdaq:        https://www.nasdaq.com/feed/rssoutbound?symbol={SYMBOL}
 *  - Seeking Alpha: https://seekingalpha.com/api/sa/combined/{SYMBOL}.xml
 */
interface RssService {
    @GET
    suspend fun fetch(@Url url: String): String
}
