package suwayomi.tachidesk.graphql.types

import eu.kanade.tachiyomi.source.model.SManga

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
    )
}
