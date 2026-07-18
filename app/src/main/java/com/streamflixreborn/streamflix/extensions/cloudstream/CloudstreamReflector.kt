// file: app/src/main/java/com/streamflixreborn/streamflix/extensions/cloudstream/CloudstreamReflector.kt
package com.streamflixreborn.streamflix.extensions.cloudstream

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.jvm.functions.Function1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Type-safe reflection utilities for interacting with Cloudstream MainAPI.
 *
 * Cloudstream plugins implement `com.lagradost.cloudstream3.MainAPI` but
 * that SDK is not available at compile time — all interaction is via reflection.
 *
 * Every method wraps reflection in [runCatching] and returns null or empty
 * results on failure, so plugin errors never crash the host app.
 */
class CloudstreamReflector {

    // ── Basic reflection helpers ───────────────────────────────────────

    /** Reads a [String] property from [obj] by field [name]. */
    fun getStringProperty(obj: Any, name: String): String? = runCatching {
        val field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(obj) as? String
    }.getOrNull()

    /** Reads a typed property from [obj] by field [name]. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getProperty(obj: Any, name: String): T? = runCatching {
        val field = obj::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(obj) as? T
    }.getOrNull()

    /** Calls a method on [obj] by name, returning the result or null. */
    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? = runCatching {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        val method = obj::class.java.getMethod(methodName, *paramTypes)
        method.invoke(obj, *args)
    }.getOrNull()

    /** Checks whether a method with the given name and parameter types exists. */
    fun hasMethod(obj: Any, methodName: String, vararg paramTypes: Class<*>): Boolean = runCatching {
        obj::class.java.getMethod(methodName, *paramTypes)
        true
    }.getOrDefault(false)

    /**
     * Calls a Kotlin suspend function via reflection.
     *
     * Kotlin suspend functions compile to JVM methods with an additional
     * [Continuation] parameter. This helper wires the coroutine machinery so
     * suspend functions can be called transparently.
     *
     * Returns the result of the suspend function, or null on failure.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> callSuspendMethod(
        obj: Any,
        methodName: String,
        vararg args: Any?,
    ): T? = suspendCoroutineUninterceptedOrReturn { cont ->
        runCatching {
            val argTypes = args.map { it?.javaClass ?: Any::class.java }
                .toTypedArray() + arrayOf(Continuation::class.java)
            val method = obj::class.java.getMethod(methodName, *argTypes)
            val result = method.invoke(obj, *args, cont)
            if (result === COROUTINE_SUSPENDED) {
                COROUTINE_SUSPENDED
            } else {
                result as? T
            }
        }.getOrNull()
    }

    // ── Cloudstream-specific helpers ───────────────────────────────────

    /**
     * Calls Cloudstream's callback-based [loadLinks] on the given plugin.
     *
     * CS3 signature (via reflection):
     * ```
     * fun loadLinks(
     *     data: String,
     *     isCasting: Boolean,
     *     subtitleCallback: (SubtitleFile) -> Unit,
     *     linkCallback: (ExtractorLink) -> Unit,
     * ): Boolean
     * ```
     *
     * We pass empty lambdas for both callbacks and collect [ExtractorLink]
     * objects from the link callback into the returned list.
     */
    suspend fun loadLinks(plugin: Any, dataId: String, videoType: Any): List<Video.Server> =
        withContext(Dispatchers.IO) {
            val links = mutableListOf<Video.Server>()
            runCatching {
                val method = plugin::class.java.getMethod(
                    "loadLinks",
                    String::class.java,
                    Boolean::class.java,
                    Function1::class.java, // subtitleCallback: (Any) -> Unit
                    Function1::class.java, // linkCallback: (Any) -> Unit
                )
                method.invoke(
                    plugin,
                    dataId,
                    false,
                    { _: Any -> /* ponytail: subtitles ignored, wire when UI needs them */ },
                    { link: Any ->
                        val name = getStringProperty(link, "name") ?: ""
                        val url = getStringProperty(link, "url") ?: ""
                        val server = Video.Server(id = url, name = name)
                        server.video = Video(source = url)
                        links.add(server)
                    },
                )
            }
            links
        }

    /**
     * Calls [getMainPage] on the plugin and converts the
     * [HomePageResponse] into Streamflix [Category] objects.
     *
     * CS3 signature: `suspend fun getMainPage(page: Int, request: HomePageRequest?): HomePageResponse`
     *
     * A [HomePageResponse] contains a list of [HomePageList] items; each
     * has a [name] and a [list] of [SearchResponse] / [LoadResponse] items.
     */
    suspend fun getMainPage(plugin: Any): List<Category> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Try non-suspend version first (older CS3), then suspend version
                val response: Any? = try {
                    plugin::class.java
                        .getMethod("getMainPage", Integer.TYPE, Any::class.java)
                        .invoke(plugin, 0, null)
                } catch (_: NoSuchMethodException) {
                    callSuspendMethod<Any>(plugin, "getMainPage", 0, null)
                }

                if (response == null) return@withContext emptyList()

                // Navigate response.homePageLists (field or getter)
                val homePageLists: List<*>? =
                    getProperty(response, "homePageLists")
                        ?: callMethod(response, "getHomePageLists") as? List<*>

                homePageLists?.mapNotNull { pageList ->
                    if (pageList == null) return@mapNotNull null
                    val name = getStringProperty(pageList, "name") ?: return@mapNotNull null
                    val items: List<*> = getProperty(pageList, "list") ?: emptyList<Any>()

                    Category(
                        name = name,
                        list = items.mapNotNull { item ->
                            if (item == null) return@mapNotNull null
                            val type = getProperty<Enum<*>>(item, "type")
                            val typeName = type?.name ?: ""
                            when {
                                typeName.contains("Movie") ->
                                    toStreamflixMovie(item)
                                typeName.contains("Series") || typeName.contains("Anime") ->
                                    toStreamflixTvShow(item)
                                else ->
                                    toStreamflixMovie(item)
                            }
                        },
                    ).apply {
                        itemType = AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    // ── Internal conversion helpers (shared with model mapper pattern) ──

    internal fun toStreamflixMovie(obj: Any): Movie? = runCatching {
        val year = getProperty<Int>(obj, "year")
        Movie(
            id = getStringProperty(obj, "url") ?: "",
            title = getStringProperty(obj, "name") ?: "",
            overview = getStringProperty(obj, "plot") ?: "",
            poster = getStringProperty(obj, "posterUrl") ?: "",
            banner = getStringProperty(obj, "bannerUrl") ?: "",
            rating = getProperty<Double>(obj, "rating"),
            released = year?.let { "$it-01-01" },
            runtime = getProperty<Int>(obj, "duration"),
        ).apply {
            itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
        }
    }.getOrNull()

    internal fun toStreamflixTvShow(obj: Any): TvShow? = runCatching {
        val year = getProperty<Int>(obj, "year")
        TvShow(
            id = getStringProperty(obj, "url") ?: "",
            title = getStringProperty(obj, "name") ?: "",
            overview = getStringProperty(obj, "plot") ?: "",
            poster = getStringProperty(obj, "posterUrl") ?: "",
            banner = getStringProperty(obj, "bannerUrl") ?: "",
            rating = getProperty<Double>(obj, "rating"),
            released = year?.let { "$it-01-01" },
            runtime = getProperty<Int>(obj, "duration"),
        ).apply {
            itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
        }
    }.getOrNull()
}
