package app.mobilemanager.di

import android.util.Log
import app.mobilemanager.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Prozessweiter Scope für Arbeit, die einen Service, Receiver oder Bildschirm überdauern muss. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Gemeinsamer Handler: ein Fehler in einer Hintergrund-Coroutine darf den Prozess nicht beenden. */
object AppCoroutines {
    fun exceptionHandler(tag: String) = CoroutineExceptionHandler { _, e ->
        if (BuildConfig.DEBUG) Log.e(tag, "Unbehandelter Fehler in Hintergrund-Coroutine", e)
    }

    /** SupervisorJob + Handler – für langlebige Scopes in Klassen, die selbst keinen Lebenszyklus haben. */
    fun scope(tag: String, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default) =
        CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler(tag))
}

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = AppCoroutines.scope("AppScope", Dispatchers.IO)
}
