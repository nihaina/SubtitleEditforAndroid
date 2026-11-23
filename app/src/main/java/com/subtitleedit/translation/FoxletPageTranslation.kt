package com.subtitleedit.translation

import android.content.Context
import android.content.res.Resources
import android.icu.util.ULocale
import io.github.yinvoker.foxlet.DeleteStatus
import io.github.yinvoker.foxlet.DownloadOptions
import io.github.yinvoker.foxlet.DownloadProgress
import io.github.yinvoker.foxlet.Foxlet
import io.github.yinvoker.foxlet.InstalledModel
import io.github.yinvoker.foxlet.LanguagePair
import io.github.yinvoker.foxlet.VerificationStatus
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object FoxletPageTranslation {
    val isSupported: Boolean by lazy {
        try {
            System.loadLibrary("foxlet")
            true
        } catch (_: LinkageError) {
            false
        }
    }

    private val clientLock = Mutex()
    private val catalogLock = Mutex()
    private val inferenceLock = Mutex()
    private var instance: Foxlet? = null
    private var modelCatalog: PageTranslationCatalog? = null

    private suspend fun client(context: Context): Foxlet = clientLock.withLock {
        check(isSupported)
        instance ?: Foxlet.create(context.applicationContext).also { instance = it }
    }

    fun systemLocale(): Locale {
        val locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
        return ULocale.addLikelySubtags(ULocale.forLocale(locale)).toLocale()
    }

    suspend fun catalog(context: Context): PageTranslationCatalog = catalogLock.withLock {
        modelCatalog ?: client(context).models.listBundled()
            .mapTo(linkedSetOf()) { it.pair.toPageModel() }
            .let(::PageTranslationCatalog)
            .also { modelCatalog = it }
    }

    suspend fun downloaded(context: Context, candidates: Set<PageTranslationModel>): Set<PageTranslationModel> {
        if (candidates.isEmpty()) return emptySet()
        val client = client(context)
        val localModels = listLocalModels(client)
        return candidates.filterTo(linkedSetOf()) { verifyFirst(client, localModels[it].orEmpty()) != null }
    }

    suspend fun openPreview(context: Context, options: PageTranslationOptions, locale: Locale): Session {
        val plan = catalog(context).plan(options, locale)
        return Session(client(context), plan, TextTranslationCache(File(context.cacheDir, "text_translation")))
    }

    private suspend fun listLocalModels(client: Foxlet): Map<PageTranslationModel, List<InstalledModel>> =
        client.models.listInstalled().groupBy { it.pair.toPageModel() }

    private suspend fun verifyFirst(client: Foxlet, installations: List<InstalledModel>): InstalledModel? {
        for (installation in installations) {
            val verified = client.models.verify(installation)
            if (verified.status == VerificationStatus.Verified) return verified.model
        }
        return null
    }

    suspend fun download(context: Context, model: PageTranslationModel, onProgress: (DownloadProgress) -> Unit) {
        val manager = client(context).models
        val descriptor = checkNotNull(manager.findBundled(model.pair))
        manager.download(descriptor, options = DownloadOptions(maxRetries = 1), onProgress = onProgress)
    }

    suspend fun delete(context: Context, model: PageTranslationModel) = inferenceLock.withLock {
        val client = client(context)
        check(client.translator.unloadModels().allReleased)
        val result = client.models.deleteAll(model.pair)
        check(result.results.all { it.status == DeleteStatus.Deleted || it.status == DeleteStatus.AlreadyAbsent })
    }

    internal class Session(
        private val client: Foxlet,
        val plan: PageTranslationPlan,
        private val textCache: TextTranslationCache,
    ) : PageTranslationSession, Closeable {
        private var identifier: PageTranslationLanguageIdentifier? = null
        private var localModels: Map<PageTranslationModel, List<InstalledModel>>? = null
        private val verifiedModels = mutableMapOf<PageTranslationModel, InstalledModel?>()

        override suspend fun missingModels(models: Collection<PageTranslationModel>): Set<PageTranslationModel> {
            if (models.isEmpty()) return emptySet()
            val installed = localModels ?: listLocalModels(client).also { localModels = it }
            return models.filterTo(linkedSetOf()) { model ->
                if (model !in verifiedModels) verifiedModels[model] = verifyFirst(client, installed[model].orEmpty())
                verifiedModels[model] == null
            }
        }

        override suspend fun identifyLanguage(text: String): PageTranslationLanguage? {
            val active = identifier ?: PageTranslationLanguageIdentifier().also { identifier = it }
            return active.identify(text, plan.systemLanguage)
        }

        override suspend fun translate(text: String, source: PageTranslationLanguage): String = inferenceLock.withLock {
            val route = checkNotNull(plan.route(source))
            try {
                when (route.size) {
                    0 -> text
                    1 -> client.translator.translate(text, verifiedModel(route.single()))
                    else -> client.translator.translatePivot(text, verifiedModel(route.first()), verifiedModel(route.last()))
                }
            } catch (unavailable: LinkageError) {
                throw IllegalStateException("Foxlet native runtime is unavailable", unavailable)
            }
        }

        override suspend fun translateDocumentChunk(text: String, source: PageTranslationLanguage): String =
            inferenceLock.withLock {
                val route = checkNotNull(plan.route(source)).map(::verifiedModel)
                val routeKey = route.joinToString("\n", prefix = "foxlet-v0.5.0\n") {
                    "${it.pair.source}:${it.pair.target}:${it.identity}"
                }
                textCache.get(routeKey, text)?.let { return@withLock it }
                var translated = text
                for (model in route) {
                    // Separate calls also run sentence and token wrapping on the intermediate text.
                    translated = client.translator.translate(translated, model)
                    check(translated.isNotBlank())
                }
                textCache.put(routeKey, text, translated)
                translated
            }

        private fun verifiedModel(model: PageTranslationModel): InstalledModel = checkNotNull(verifiedModels[model])

        override fun close() {
            identifier?.close()
        }
    }

    private val PageTranslationModel.pair get() = LanguagePair(source.tag, target.tag)

    private fun LanguagePair.toPageModel() = PageTranslationModel(PageTranslationLanguage(source), PageTranslationLanguage(target))
}
