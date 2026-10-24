package suwayomi.tachidesk.graphql.types

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A source result that is deliberately not backed by a persisted manga row. */
data class SourceMangaPreviewType(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val artist: String?,
    val author: String?,
    val description: String?,
    val genre: String?,
    val status: Int,
    val backend: String?,
) {
    constructor(sourceId: Long, manga: SManga) : this(
        sourceId = sourceId,
        url = manga.url,
        title = manga.title,
        thumbnailUrl = manga.thumbnail_url,
        artist = manga.artist,
        author = manga.author,
        description = manga.description,
        genre = manga.genre,
        status = manga.status,
        backend = manga.memo["suwa_backend"]?.jsonPrimitive?.contentOrNull,
    )
}
