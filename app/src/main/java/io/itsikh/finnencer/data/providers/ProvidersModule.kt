package io.itsikh.finnencer.data.providers

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvidersModule {

    @Binds @IntoSet
    abstract fun bindFinnhub(impl: FinnhubNewsProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindNasdaq(impl: NasdaqRssProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindSeekingAlpha(impl: SeekingAlphaRssProvider): NewsProvider

    @Binds @IntoSet
    abstract fun bindEdgar(impl: EdgarFilingsProvider): NewsProvider
}
