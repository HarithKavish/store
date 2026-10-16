package com.harithkavish.store.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Loads catalogue icons, keeping them in memory so scrolling and every re-render (a filter, a
 * search keystroke, a download finishing) does not fetch them again. Square PNGs are shown
 * with rounded corners, like the website's icons.
 */
class IconLoader(private val scope: CoroutineScope) {

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String?, previous: Job?): Job? {
        previous?.cancel()
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return null
        }
        cache.get(url)?.let {
            show(view, it)
            return null
        }
        view.setImageDrawable(null)
        return scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            } ?: return@launch
            cache.put(url, bitmap)
            // The view may have been rebound to another app while this loaded.
            if (view.tag == url) show(view, bitmap)
        }
    }

    /**
     * Rounds the bitmap itself rather than clipping the view to its outline, which not every
     * device honours. A quarter of the width matches the cards' rounded-square icon slots.
     */
    private fun show(view: ImageView, bitmap: Bitmap) {
        val rounded = RoundedBitmapDrawableFactory.create(view.resources, bitmap)
        rounded.cornerRadius = bitmap.width * 0.24f
        rounded.setAntiAlias(true)
        view.setImageDrawable(rounded)
    }
}
