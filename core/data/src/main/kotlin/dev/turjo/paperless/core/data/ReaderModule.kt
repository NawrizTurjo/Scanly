package dev.turjo.paperless.core.data

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.turjo.paperless.core.pdf.AndroidPdfSessionManager
import dev.turjo.paperless.core.pdf.PdfSessionManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReaderModule {
    @Binds
    abstract fun bindReaderRepository(repository: RoomReaderRepository): ReaderRepository

    @Binds
    abstract fun bindPdfSaveService(service: SafPdfSaveService): PdfSaveService

    @Binds
    abstract fun bindPdfSessionManager(manager: AndroidPdfSessionManager): PdfSessionManager

    companion object {
        @Provides
        @Singleton
        fun providePdfSessionManager(
            @ApplicationContext context: Context,
            repository: RoomReaderRepository,
        ): AndroidPdfSessionManager = AndroidPdfSessionManager(context, repository::resolveUri)
    }
}
