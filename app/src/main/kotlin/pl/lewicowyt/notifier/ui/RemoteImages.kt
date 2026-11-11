package pl.lewicowyt.notifier.ui

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.lewicowyt.notifier.images.CommunityImageLoader
import pl.lewicowyt.notifier.images.JxlImageCache

@Composable
fun ProfileImage(
    url: String?,
    creatorName: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberRemoteBitmap(url)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clearAndSetSemantics { }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = creatorName.trim().firstOrNull()?.uppercase() ?: "?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun VideoThumbnail(
    videoId: String,
    modifier: Modifier = Modifier,
) {
    // bierzemy hqdefault (480×360) prosto z CDN YouTube zamiast sddefault (640×480)
    // zmniejszanego potem u nas – mniej transferu, RAM-u i roboty dekodera dla kafelka 128×72dp.
    // WebP (`/vi_webp/….webp`) jest mniejszy niż JPG; JxlImageCache ma fallback na JPG, gdy brak WebP.
    val url = "https://i.ytimg.com/vi_webp/$videoId/hqdefault.webp"
    val bitmap by rememberRemoteBitmap(url)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clearAndSetSemantics { }
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "YouTube",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rememberRemoteBitmap(url: String?): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = if (url.isNullOrBlank()) null else JxlImageCache.load(context, url)
    }
}

@Composable
internal fun rememberCommunityBitmap(
    url: String?,
    jxlQuality: Int? = null,
): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState<Bitmap?>(initialValue = null, key1 = url, key2 = jxlQuality) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        // progresywny JXL: dopóki obraz się pobiera, pokazujemy jego kolejne, coraz ostrzejsze podglądy
        val previews = launch {
            CommunityImageLoader.previews.collect { (previewUrl, preview) -> if (previewUrl == url) value = preview }
        }
        val full = CommunityImageLoader.load(context, url, jxlQuality)
        previews.cancel()
        value = full // brak pełnego obrazu (błąd) = nie zostawiamy nieostrego podglądu
    }
}

/** avatar twórcy z serwera społeczności (JXL); gdy brak – kółko z inicjałem. */
@Composable
fun CommunityAvatar(url: String?, name: String, modifier: Modifier = Modifier) {
    val bitmap by rememberCommunityBitmap(url)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clearAndSetSemantics { }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Jeden slajd karuzeli. NA FRONCIE całe zdjęcie (Fit — nic nie przycinamy), a W TLE to samo zdjęcie
 * przeskalowane do WYPEŁNIENIA (Crop) i ROZMYTE. Rozmycie to filtr graficzny w czasie działania
 * (Modifier.blur) — nie zapisujemy żadnych rozmazanych kopii. Dzięki temu zdjęcie o innych proporcjach
 * niż kontener widać w całości, a „puste" boki wypełnia rozmyte tło z tego samego zdjęcia (efekt jak w IG).
 * Uwaga: Modifier.blur działa od Androida 12 (API 31); na starszych tło po prostu nie jest rozmyte
 * (nadal wypełnia kadr tym samym zdjęciem — bez brzydkich pustych pasów).
 */
@Composable
internal fun CommunityCarouselSlide(bitmap: Bitmap?, modifier: Modifier = Modifier, description: String? = null) {
    Box(modifier) {
        bitmap?.let {
            val img = it.asImageBitmap()
            Image(
                bitmap = img,
                contentDescription = null, // rozmyte tło — dekoracja, TalkBack je pomija
                modifier = Modifier.matchParentSize().blur(22.dp),
                contentScale = ContentScale.Crop, // tło: wypełnij kadr (może przyciąć — to tylko tło)
            )
            Image(
                bitmap = img,
                contentDescription = description,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit, // front: całe zdjęcie, bez przycinania
            )
        }
    }
}

/** obraz pozycji z serwera społeczności (JXL/AVIF/WEBP); nic nie rysuje, gdy się nie załaduje. */
@Composable
fun CommunityFeedImage(
    url: String?,
    modifier: Modifier = Modifier,
    jxlQuality: Int? = null,
    // TalkBack: opis obrazu (np. „Obraz mema: tytuł"); null = obraz dekoracyjny, czytnik go pomija
    description: String? = null,
) {
    if (url.isNullOrBlank()) return
    val bitmap by rememberCommunityBitmap(url, jxlQuality)
    val animated by rememberCommunityAnimation(url)
    val anim = animated
    if (anim != null) {
        CommunityAnimatedImage(anim, description, modifier)
        return
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = description,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * Animowany WebP (Android 9+). Przy systemowym „Usuń animacje" (skala animacji = 0) zostaje zwykła pierwsza
 * klatka. Poza ekranem LazyColumn usuwa widok, a my zatrzymujemy animację (bateria).
 */
@Composable
private fun rememberCommunityAnimation(url: String): androidx.compose.runtime.State<Drawable?> {
    val context = LocalContext.current.applicationContext
    return produceState<Drawable?>(initialValue = null, key1 = url) {
        val reducedMotion = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        value = if (reducedMotion) null else CommunityImageLoader.loadAnimated(context, url)
    }
}

@Composable
private fun CommunityAnimatedImage(drawable: Drawable, description: String?, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = description
                setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
            }
        },
        // D-19i: nowy drawable w tym samym miejscu kompozycji — zatrzymaj starą animację i pokaż nową
        update = { view ->
            if (view.drawable !== drawable) {
                (view.drawable as? Animatable)?.stop()
                view.setImageDrawable(drawable)
                (drawable as? Animatable)?.start()
            }
            view.contentDescription = description
        },
        onRelease = { view -> (view.drawable as? Animatable)?.stop() },
        modifier = modifier,
    )
}
