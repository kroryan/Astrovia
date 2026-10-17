@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import com.kroryan.astrovia.data.net.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Downloads and caches the tiles of a pyramid; [onChange] is called (on any thread) when a tile arrives. */
class TileLoader(private val scope: CoroutineScope, private val onChange: () -> Unit) {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(6)

    fun peek(url: String): Bitmap? = cache.get(url)

    /** Returns the tile if it is cached, otherwise starts downloading it. */
    fun request(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in failed || !inFlight.add(url)) return null
        scope.launch(Dispatchers.IO) {
            try {
                gate.withPermit {
                    val bmp = fetch(url)
                    if (bmp != null) cache.put(url, bmp) else failed.add(url)
                }
            } finally {
                inFlight.remove(url)
                onChange()
            }
        }
        return null
    }

    companion object {
        fun fetch(url: String): Bitmap? = try {
            HttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        } catch (e: Exception) { null }

        fun fetchText(url: String): String? = try {
            HttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (!r.isSuccessful) null else r.body?.string() }
        } catch (e: Exception) { null }
    }
}
