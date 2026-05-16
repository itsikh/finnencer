package io.itsikh.finnencer.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.itsikh.finnencer.data.dao.AiJobDao
import io.itsikh.finnencer.data.dao.ApiUsageDao
import io.itsikh.finnencer.data.dao.EarningsDao
import io.itsikh.finnencer.data.dao.NewsDao
import io.itsikh.finnencer.data.dao.NotificationDao
import io.itsikh.finnencer.data.dao.PodcastDao
import io.itsikh.finnencer.data.dao.TickerDao
import io.itsikh.finnencer.data.db.FinnencerDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FinnencerDatabase =
        Room.databaseBuilder(context, FinnencerDatabase::class.java, FinnencerDatabase.NAME)
            // No fallbackToDestructiveMigration — until schema stabilises we'll
            // bump version + migrate. The DB is small and rebuilding it on first
            // mismatch is acceptable for personal-use scope.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideTickerDao(db: FinnencerDatabase): TickerDao = db.tickerDao()
    @Provides fun provideNewsDao(db: FinnencerDatabase): NewsDao = db.newsDao()
    @Provides fun provideNotificationDao(db: FinnencerDatabase): NotificationDao = db.notificationDao()
    @Provides fun provideEarningsDao(db: FinnencerDatabase): EarningsDao = db.earningsDao()
    @Provides fun providePodcastDao(db: FinnencerDatabase): PodcastDao = db.podcastDao()
    @Provides fun provideApiUsageDao(db: FinnencerDatabase): ApiUsageDao = db.apiUsageDao()
    @Provides fun provideAiJobDao(db: FinnencerDatabase): AiJobDao = db.aiJobDao()
}
