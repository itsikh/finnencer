package io.itsikh.finnencer.data.providers

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersModule {

    // Free, broad-coverage providers — these run on every sync.
    @Binds @IntoSet
    abstract fun bindGoogleNews(impl: GoogleNewsRssProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindYahooFinance(impl: YahooFinanceRssProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindNasdaq(impl: NasdaqRssProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindSeekingAlpha(impl: SeekingAlphaRssProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindEdgar(impl: EdgarFilingsProvider): NewsProvider

    // Finnhub /company-news intentionally NOT in the news pipeline.
    // The Finnhub Retrofit service stays around for ticker auto-complete
    // (FinnhubService.search) in WatchlistRepository — free tier only,
    // no per-article ingestion.
}
