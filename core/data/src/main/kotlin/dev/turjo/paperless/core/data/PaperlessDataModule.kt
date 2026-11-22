package dev.turjo.paperless.core.data

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PaperlessDataModule {
    @Binds
    @Singleton
    abstract fun bindDocumentRepository(repository: RoomDocumentRepository): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindScanSessionRepository(repository: RoomScanSessionRepository): ScanSessionRepository

    @Binds
    @Singleton
    abstract fun bindPageProcessing(processing: QueuedPageProcessing): PageProcessing

    @Binds
    @Singleton
    abstract fun bindFolderRepository(repository: RoomFolderRepository): FolderRepository

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): PaperlessDatabase =
            Room.databaseBuilder(context, PaperlessDatabase::class.java, "paperless.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        @Provides
        fun provideDocumentDao(database: PaperlessDatabase): DocumentDao = database.documentDao()

        @Provides
        fun provideScanSessionDao(database: PaperlessDatabase): ScanSessionDao = database.scanSessionDao()
    }
}
