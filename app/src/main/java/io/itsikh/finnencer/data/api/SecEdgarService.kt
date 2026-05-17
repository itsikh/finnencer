package io.itsikh.finnencer.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * SEC EDGAR endpoints used by finnencer. CIK is the zero-padded 10-digit
 * Central Index Key for a registrant. Submissions JSON is the primary feed
 * for new filings.  Filled out in Build A·7 and A·12.
 */
interface SecEdgarService {

    /** Per-CIK Atom RSS of recent filings — caller passes full URL. */
    @GET
    suspend fun companyAtom(@Url url: String): String

    /** Submissions JSON: https://data.sec.gov/submissions/CIK{cik}.json */
    @GET("submissions/CIK{cik}.json")
    suspend fun submissions(@Path("cik") cikZeroPadded10: String): String

    /**
     * Parsed XBRL company facts. Returns the company's full filing history
     * for every us-gaap concept (Revenues, EarningsPerShareDiluted,
     * GrossProfit, NetIncomeLoss, etc.) keyed by metric. Each entry has
     * fiscal year / period, period start / end, form (8-K / 10-Q / 10-K),
     * and the reported value. SEC parses this from each company's XBRL
     * filing — no third party in the loop. Free, no auth beyond the
     * standard EDGAR User-Agent. Endpoint:
     * https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json
     */
    @GET("api/xbrl/companyfacts/CIK{cik}.json")
    suspend fun companyFacts(@Path("cik") cikZeroPadded10: String): String

    /** Ticker→CIK lookup table. ~1MB JSON; caller should cache. */
    @GET("https://www.sec.gov/files/company_tickers.json")
    suspend fun tickerCikMap(): String
}
