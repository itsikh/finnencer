package io.itsikh.finnencer.ui.screens.snapshot

/**
 * Plain-English definitions for the metrics on the snapshot screen.
 * Surfaced in a dialog when the user taps any metric label.
 */
data class MetricInfo(val label: String, val definition: String)

object MetricDefinitions {
    val FIFTY_TWO_WEEK_RANGE = MetricInfo(
        label = "52-week range",
        definition = "The lowest and highest prices the stock traded at over the past 52 weeks. " +
            "Tells you where today's price sits within the past year — near the high suggests strong momentum, " +
            "near the low suggests a selloff or unloved name.",
    )

    val MARKET_CAP = MetricInfo(
        label = "Market cap",
        definition = "Total dollar value of all outstanding shares (shares outstanding × current price). " +
            "Defines the company's size class: large-cap (>\$10B), mid-cap (\$2–10B), small-cap (\$300M–2B). " +
            "Big caps are typically less volatile; small caps move faster on news.",
    )

    val PE_TTM = MetricInfo(
        label = "P/E (TTM)",
        definition = "Price-to-Earnings ratio over the trailing 12 months — current price divided by last 12 months of " +
            "earnings per share. How many dollars investors pay for \$1 of earnings. " +
            "Lower P/E often signals a value name; higher P/E often signals growth expectations or richness.",
    )

    val EPS_TTM = MetricInfo(
        label = "EPS (TTM)",
        definition = "Earnings per share over the trailing 12 months. Net profit divided by shares outstanding. " +
            "A positive growing EPS is a healthy sign; negative EPS means the company lost money last year.",
    )

    val BETA = MetricInfo(
        label = "Beta",
        definition = "Volatility vs the broader market (S&P 500). Beta of 1.0 means moves with the market; " +
            "Beta > 1 means more volatile (amplifies market moves); Beta < 1 means more stable. " +
            "High-beta names amplify both gains and losses on market-wide moves.",
    )

    val DIV_YIELD = MetricInfo(
        label = "Dividend yield",
        definition = "Annual dividend per share as a percentage of the current price. " +
            "A 3% yield on a \$100 stock means \$3/year in cash dividends per share. " +
            "Mature, profitable companies often pay dividends; growth companies usually don't.",
    )

    val REV_GROWTH_YOY = MetricInfo(
        label = "Revenue growth YoY",
        definition = "Year-over-year change in revenue (trailing 12 months vs the prior 12). " +
            "A growth gauge — single-digit growth is mature/cyclical; 20%+ is hyper-growth. " +
            "Watch for deceleration: shrinking growth rates often precede multiple compression.",
    )

    val PRICE_TO_SALES = MetricInfo(
        label = "Price-to-sales",
        definition = "Market cap divided by annual revenue. Useful when earnings are negative or volatile " +
            "(early-stage growth, cyclicals). High P/S means investors are pricing in future growth; " +
            "low P/S suggests a mature or unloved name.",
    )

    val AVG_VOL = MetricInfo(
        label = "Average volume",
        definition = "Average number of shares traded per day over the lookback window. " +
            "Higher volume means more liquidity (easier to enter/exit without moving price). " +
            "A sudden spike vs the average often signals a catalyst — news, earnings, or institutional flow.",
    )

    val DAY_RANGE = MetricInfo(
        label = "Day range",
        definition = "Low and high prices traded today. A wide range vs the average daily move " +
            "suggests volatility or fresh news; a narrow range suggests low conviction either direction.",
    )

    val PRICE = MetricInfo(
        label = "Current price",
        definition = "Last traded price during regular market hours. Yahoo's data is delayed up to ~15 minutes; " +
            "treat it as recent, not real-time.",
    )

    val PCT_CHANGE = MetricInfo(
        label = "Today's change",
        definition = "Percent change from yesterday's closing price to the current price. " +
            "Note: extended-hours trading may shift this from what you'd see at the open.",
    )
}
