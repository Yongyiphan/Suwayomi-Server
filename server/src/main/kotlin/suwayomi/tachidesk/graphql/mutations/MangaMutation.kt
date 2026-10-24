@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.server.extensions.toGraphQLError
import graphql.execution.DataFetcherResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.ChapterType
import suwayomi.tachidesk.graphql.types.MangaMetaType
import suwayomi.tachidesk.graphql.types.MangaType
import suwayomi.tachidesk.graphql.types.MetaInput
import suwayomi.tachidesk.manga.impl.Library
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.update.IUpdater
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.CategoryMangaTable
import suwayomi.tachidesk.manga.model.table.MangaMetaTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.manga.model.table.getWithUserData
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.server.JavalinSetup.future
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * TODO Mutations
 * - Download x(all = -1) chapters
 * - Delete read/all downloaded chapters
 */
class MangaMutation {
    private val updater: IUpdater by injectLazy()

    data class UpdateMangaPatch(
        val inLibrary: Boolean? = null,
    )

    data class UpdateMangaPayload(
        val clientMutationId: String?,
        val manga: MangaType,
    )

    data class UpdateMangaInput(
        val clientMutationId: String? = null,
        val id: Int,
        val patch: UpdateMangaPatch,
    )

    data class UpdateMangasPayload(
        val clientMutationId: String?,
        val mangas: List<MangaType>,
    )

    data class UpdateMangasInput(
        val clientMutationId: String? = null,
        val ids: List<Int>,
        val patch: UpdateMangaPatch,
    )

    data class MergeMangaRecordsInput(
        val clientMutationId: String? = null,
        val winnerId: Int,
        val loserIds: List<Int>,
    )

    data class MergeMangaRecordsPayload(
        val clientMutationId: String?,
        val mergedIds: List<Int>,
        val skippedIds: List<Int>,
    )

    /** Merge safe duplicate records while preserving chapters and user state. */
    @RequireAuth
    fun mergeMangaRecords(
        @GraphQLIgnore
        userId: Int,
        input: MergeMangaRecordsInput,
    ): CompletableFuture<MergeMangaRecordsPayload?> {
        val (clientMutationId, winnerId, loserIds) = input

        return future {
            val (mergedIds, skippedIds) = transaction {
                require(MangaTable.selectAll().where { MangaTable.id eq winnerId }.any()) {
                    "Winner manga does not exist: $winnerId"
                }

                val merged = mutableListOf<Int>()
                val skipped = mutableListOf<Int>()
                loserIds.distinct().filter { it != winnerId }.forEach { loserId ->
                    val libraryRow =
                        MangaUserTable
                            .selectAll()
                            .where { (MangaUserTable.manga eq loserId) and (MangaUserTable.user eq userId) }
                            .singleOrNull()
                    val isInLibrary = libraryRow?.get(MangaUserTable.inLibrary) ?: false
                    val chapterIds =
                        ChapterTable
                            .select(ChapterTable.id)
                            .where { ChapterTable.manga eq loserId }
                            .map { it[ChapterTable.id].value }
                    val hasActiveDownloads =
                        chapterIds.isNotEmpty() &&
                            ChapterUserTable
                                .selectAll()
                                .where {
                                    (ChapterUserTable.chapter inList chapterIds) and
                                        (ChapterUserTable.isDownloaded or ChapterUserTable.isDownloadRequested)
                                }
                                .any()

                    if (isInLibrary || hasActiveDownloads) {
                        skipped += loserId
                    } else {
                        CategoryMangaTable
                            .selectAll()
                            .where { CategoryMangaTable.manga eq loserId }
                            .forEach { row ->
                                val categoryId = row[CategoryMangaTable.category].value
                                val categoryUserId = row[CategoryMangaTable.user].value
                                val alreadyLinked =
                                    CategoryMangaTable
                                        .selectAll()
                                        .where {
                                            (CategoryMangaTable.category eq categoryId) and
                                                (CategoryMangaTable.manga eq winnerId) and
                                                (CategoryMangaTable.user eq categoryUserId)
                                        }
                                        .any()
                                if (!alreadyLinked) {
                                    CategoryMangaTable.insert {
                                        it[category] = categoryId
                                        it[manga] = winnerId
                                        it[user] = categoryUserId
                                    }
                                }
                            }
                        CategoryMangaTable.deleteWhere { CategoryMangaTable.manga eq loserId }

                        MangaMetaTable
                            .selectAll()
                            .where { MangaMetaTable.ref eq loserId }
                            .forEach { row ->
                                val metaUserId = row[MangaMetaTable.user].value
                                val metaKey = row[MangaMetaTable.key]
                                val alreadyPresent =
                                    MangaMetaTable
                                        .selectAll()
                                        .where {
                                            (MangaMetaTable.ref eq winnerId) and
                                                (MangaMetaTable.user eq metaUserId) and
                                                (MangaMetaTable.key eq metaKey)
                                        }
                                        .any()
                                if (!alreadyPresent) {
                                    MangaMetaTable.insert {
                                        it[key] = metaKey
                                        it[value] = row[MangaMetaTable.value]
                                        it[ref] = winnerId
                                        it[user] = metaUserId
                                    }
                                }
                            }
                        MangaMetaTable.deleteWhere { MangaMetaTable.ref eq loserId }

                        val winnerChaptersByUrl =
                            ChapterTable
                                .select(ChapterTable.id, ChapterTable.url)
                                .where { ChapterTable.manga eq winnerId }
                                .associate { it[ChapterTable.url] to it[ChapterTable.id].value }
                        ChapterTable
                            .selectAll()
                            .where { ChapterTable.manga eq loserId }
                            .forEach { loserChapter ->
                                val loserChapterId = loserChapter[ChapterTable.id].value
                                val winnerChapterId = winnerChaptersByUrl[loserChapter[ChapterTable.url]]
                                if (winnerChapterId == null) {
                                    ChapterTable.update({ ChapterTable.id eq loserChapterId }) {
                                        it[ChapterTable.manga] = winnerId
                                    }
                                } else {
                                    ChapterUserTable
                                        .selectAll()
                                        .where { ChapterUserTable.chapter eq loserChapterId }
                                        .forEach { loserUser ->
                                            val chapterUserId = loserUser[ChapterUserTable.user].value
                                            val winnerUser =
                                                ChapterUserTable
                                                    .selectAll()
                                                    .where {
                                                        (ChapterUserTable.chapter eq winnerChapterId) and
                                                            (ChapterUserTable.user eq chapterUserId)
                                                    }
                                                    .singleOrNull()
                                            if (winnerUser == null) {
                                                ChapterUserTable.insert {
                                                    it[chapter] = winnerChapterId
                                                    it[user] = chapterUserId
                                                    it[isRead] = loserUser[ChapterUserTable.isRead]
                                                    it[isBookmarked] = loserUser[ChapterUserTable.isBookmarked]
                                                    it[lastPageRead] = loserUser[ChapterUserTable.lastPageRead]
                                                    it[lastReadAt] = loserUser[ChapterUserTable.lastReadAt]
                                                    it[isDownloaded] = loserUser[ChapterUserTable.isDownloaded]
                                                    it[isDownloadRequested] = loserUser[ChapterUserTable.isDownloadRequested]
                                                    it[koreaderHash] = loserUser[ChapterUserTable.koreaderHash]
                                                    it[version] = loserUser[ChapterUserTable.version]
                                                    it[isSyncing] = loserUser[ChapterUserTable.isSyncing]
                                                    it[lastModifiedAt] = loserUser[ChapterUserTable.lastModifiedAt]
                                                }
                                            } else {
                                                ChapterUserTable.update({ ChapterUserTable.id eq winnerUser[ChapterUserTable.id] }) {
                                                    it[isRead] = winnerUser[ChapterUserTable.isRead] || loserUser[ChapterUserTable.isRead]
                                                    it[isBookmarked] = winnerUser[ChapterUserTable.isBookmarked] || loserUser[ChapterUserTable.isBookmarked]
                                                    it[lastPageRead] = maxOf(winnerUser[ChapterUserTable.lastPageRead], loserUser[ChapterUserTable.lastPageRead])
                                                    it[lastReadAt] = maxOf(winnerUser[ChapterUserTable.lastReadAt], loserUser[ChapterUserTable.lastReadAt])
                                                    it[isDownloaded] = winnerUser[ChapterUserTable.isDownloaded] || loserUser[ChapterUserTable.isDownloaded]
                                                    it[isDownloadRequested] = winnerUser[ChapterUserTable.isDownloadRequested] || loserUser[ChapterUserTable.isDownloadRequested]
                                                }
                                            }
                                        }
                                    ChapterTable.deleteWhere { ChapterTable.id eq loserChapterId }
                                }
                            }
                        MangaUserTable.deleteWhere { MangaUserTable.manga eq loserId }
                        MangaTable.deleteWhere { MangaTable.id eq loserId }
                        merged += loserId
                    }
                }
                merged to skipped
            }
            MergeMangaRecordsPayload(clientMutationId, mergedIds, skippedIds)
        }
    }

    private suspend fun updateMangas(
        @GraphQLIgnore
        userId: Int,
        ids: List<Int>,
        patch: UpdateMangaPatch,
    ) {
        transaction {
            if (patch.inLibrary != null) {
                val now = Instant.now().epochSecond
                MangaUserTable.batchUpsert(ids, MangaUserTable.user, MangaUserTable.manga) { mangaId ->
                    this[MangaUserTable.manga] = mangaId
                    this[MangaUserTable.user] = userId
                    patch.inLibrary.also {
                        this[MangaUserTable.inLibrary] = it
                        if (it) this[MangaUserTable.inLibraryAt] = now
                    }
                }
            }
        }.apply {
            if (patch.inLibrary != null) {
                transaction {
                    // try to initialize uninitialized in library manga to ensure that the expected data is available (chapter list, metadata, ...)
                    val mangas =
                        transaction {
                            MangaTable
                                .selectAll()
                                .where { (MangaTable.id inList ids) and (MangaTable.initialized eq false) }
                                .map { MangaTable.toDataClass(it) }
                        }

                    updater.addMangasToQueue(mangas, userId)
                }

                ids.forEach {
                    Library.handleMangaThumbnail(it)
                }
            }
        }
    }

    @RequireAuth
    fun updateManga(
        @GraphQLIgnore
        userId: Int,
        input: UpdateMangaInput,
    ): CompletableFuture<UpdateMangaPayload?> {
        val (clientMutationId, id, patch) = input

        return future {
            updateMangas(userId, listOf(id), patch)

            val manga =
                transaction {
                    MangaType(
                        MangaTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { MangaTable.id eq id }
                            .first(),
                    )
                }

            UpdateMangaPayload(
                clientMutationId = clientMutationId,
                manga = manga,
            )
        }
    }

    @RequireAuth
    fun updateMangas(
        @GraphQLIgnore
        userId: Int,
        input: UpdateMangasInput,
    ): CompletableFuture<UpdateMangasPayload?> {
        val (clientMutationId, ids, patch) = input

        return future {
            updateMangas(userId, ids, patch)

            val mangas =
                transaction {
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id inList ids }
                        .map { MangaType(it) }
                }

            UpdateMangasPayload(
                clientMutationId = clientMutationId,
                mangas = mangas,
            )
        }
    }

    data class FetchMangaInput(
        val clientMutationId: String? = null,
        val id: Int,
    )

    data class FetchMangaPayload(
        val clientMutationId: String?,
        val manga: MangaType,
    )

    @RequireAuth
    @GraphQLDeprecated("Deprecated in Tachiyomix 1.6", ReplaceWith("fetchMangaAndChapters"))
    fun fetchManga(
        @GraphQLIgnore
        userId: Int,
        input: FetchMangaInput,
    ): CompletableFuture<FetchMangaPayload?> {
        val (clientMutationId, id) = input

        return future {
            Manga.updateMangaAndChapters(id, updateChapters = false)

            val manga =
                transaction {
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id eq id }
                        .first()
                }
            FetchMangaPayload(
                clientMutationId = clientMutationId,
                manga = MangaType(manga),
            )
        }
    }

    data class FetchMangaAndChaptersInput(
        val clientMutationId: String? = null,
        val id: Int,
        val fetchManga: Boolean,
        val fetchChapters: Boolean,
    )

    data class FetchMangaAndChaptersPayload(
        val clientMutationId: String?,
        val manga: MangaType,
        val chapters: List<ChapterType>,
    )

    @RequireAuth
    fun fetchMangaAndChapters(
        @GraphQLIgnore
        userId: Int,
        input: FetchMangaAndChaptersInput,
    ): CompletableFuture<DataFetcherResult<FetchMangaAndChaptersPayload?>> {
        val (clientMutationId, id, fetchManga, fetchChapters) = input

        return future {
            val error =
                try {
                    Manga.updateMangaAndChapters(
                        mangaId = id,
                        updateManga = fetchManga,
                        updateChapters = fetchChapters,
                    )
                    null
                } catch (e: Exception) {
                    KotlinLogging.logger { }.error(e) { "Error updating manga and chapters" }
                    e
                }

            val (manga, chapters) =
                transaction {
                    Pair(
                        MangaTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { MangaTable.id eq id }
                            .first(),
                        ChapterTable
                            .getWithUserData(userId)
                            .selectAll()
                            .where { ChapterTable.manga eq id }
                            .orderBy(ChapterTable.sourceOrder)
                            .map { ChapterType(it) },
                    )
                }
            @Suppress("UNCHECKED_CAST")
            DataFetcherResult
                .newResult<FetchMangaAndChaptersPayload>()
                .data(
                    FetchMangaAndChaptersPayload(
                        clientMutationId = clientMutationId,
                        manga = MangaType(manga),
                        chapters = chapters,
                    ),
                ).also {
                    if (error != null) {
                        it.error(error.toGraphQLError())
                    }
                }.build() as DataFetcherResult<FetchMangaAndChaptersPayload?>
        }
    }

    data class SetMangaMetaInput(
        val clientMutationId: String? = null,
        val meta: MangaMetaType,
    )

    data class SetMangaMetaPayload(
        val clientMutationId: String?,
        val meta: MangaMetaType,
    )

    @RequireAuth
    fun setMangaMeta(
        @GraphQLIgnore
        userId: Int,
        input: SetMangaMetaInput,
    ): SetMangaMetaPayload? {
        val (clientMutationId, meta) = input

        Manga.modifyMangaMeta(userId, meta.mangaId, meta.key, meta.value)

        return SetMangaMetaPayload(clientMutationId, meta)
    }

    data class DeleteMangaMetaInput(
        val clientMutationId: String? = null,
        val mangaId: Int,
        val key: String,
    )

    data class DeleteMangaMetaPayload(
        val clientMutationId: String?,
        val meta: MangaMetaType?,
        val manga: MangaType,
    )

    @RequireAuth
    fun deleteMangaMeta(
        @GraphQLIgnore
        userId: Int,
        input: DeleteMangaMetaInput,
    ): DeleteMangaMetaPayload? {
        val (clientMutationId, mangaId, key) = input

        val (meta, manga) =
            transaction {
                val meta =
                    MangaMetaTable
                        .selectAll()
                        .where {
                            MangaMetaTable.user eq userId and
                                (MangaMetaTable.ref eq mangaId) and
                                (MangaMetaTable.key eq key)
                        }.firstOrNull()

                MangaMetaTable.deleteWhere {
                    MangaMetaTable.user eq userId and
                        (MangaMetaTable.ref eq mangaId) and
                        (MangaMetaTable.key eq key)
                }

                val manga =
                    transaction {
                        MangaType(
                            MangaTable
                                .getWithUserData(userId)
                                .selectAll()
                                .where { MangaTable.id eq mangaId }
                                .first(),
                        )
                    }

                if (meta != null) {
                    MangaMetaType(meta)
                } else {
                    null
                } to manga
            }

        return DeleteMangaMetaPayload(clientMutationId, meta, manga)
    }

    data class SetMangaMetasItem(
        val mangaIds: List<Int>,
        val metas: List<MetaInput>,
    )

    data class SetMangaMetasInput(
        val clientMutationId: String? = null,
        val items: List<SetMangaMetasItem>,
    )

    data class SetMangaMetasPayload(
        val clientMutationId: String?,
        val metas: List<MangaMetaType>,
        val mangas: List<MangaType>,
    )

    @RequireAuth
    fun setMangaMetas(
        @GraphQLIgnore
        userId: Int,
        input: SetMangaMetasInput,
    ): SetMangaMetasPayload? {
        val (clientMutationId, items) = input

        val metaByMangaId =
            items
                .flatMap { item ->
                    val metaMap = item.metas.associate { it.key to it.value }
                    item.mangaIds.map { mangaId -> mangaId to metaMap }
                }.groupBy({ it.first }, { it.second })
                .mapValues { (_, maps) -> maps.reduce { acc, map -> acc + map } }

        Manga.modifyMangasMetas(userId, metaByMangaId)

        val allMangaIds = metaByMangaId.keys
        val allMetaKeys = metaByMangaId.values.flatMap { it.keys }.distinct()

        val (updatedMetas, mangas) =
            transaction {
                val updatedMetas =
                    MangaMetaTable
                        .selectAll()
                        .where {
                            (MangaMetaTable.user eq userId) and (MangaMetaTable.ref inList allMangaIds) and
                                (MangaMetaTable.key inList allMetaKeys)
                        }.map { MangaMetaType(it) }

                val mangas =
                    MangaTable
                        .getWithUserData(userId)
                        .selectAll()
                        .where { MangaTable.id inList allMangaIds }
                        .map { MangaType(it) }
                        .distinctBy { it.id }

                updatedMetas to mangas
            }

        return SetMangaMetasPayload(clientMutationId, updatedMetas, mangas)
    }

    data class DeleteMangaMetasItem(
        val mangaIds: List<Int>,
        val keys: List<String>? = null,
        val prefixes: List<String>? = null,
    )

    data class DeleteMangaMetasInput(
        val clientMutationId: String? = null,
        val items: List<DeleteMangaMetasItem>,
    )

    data class DeleteMangaMetasPayload(
        val clientMutationId: String?,
        val metas: List<MangaMetaType>,
        val mangas: List<MangaType>,
    )

    @RequireAuth
    fun deleteMangaMetas(
        @GraphQLIgnore
        userId: Int,
        input: DeleteMangaMetasInput,
    ): DeleteMangaMetasPayload? {
        val (clientMutationId, items) = input

        items.forEach { item ->
            require(!item.keys.isNullOrEmpty() || !item.prefixes.isNullOrEmpty()) {
                "Either 'keys' or 'prefixes' must be provided for each item"
            }
        }

        val (allDeletedMetas, allMangaIds) =
            transaction {
                val deletedMetas = mutableListOf<MangaMetaType>()
                val mangaIds = mutableSetOf<Int>()

                items.forEach { item ->
                    val keyCondition: Op<Boolean>? =
                        item.keys?.takeIf { it.isNotEmpty() }?.let { MangaMetaTable.key inList it }

                    val prefixCondition: Op<Boolean>? =
                        item.prefixes
                            ?.filter { it.isNotEmpty() }
                            ?.map { (MangaMetaTable.key like LikePattern("$it%")) as Op<Boolean> }
                            ?.reduceOrNull { acc, op -> acc or op }

                    val metaKeyCondition =
                        if (keyCondition != null && prefixCondition != null) {
                            keyCondition or prefixCondition
                        } else {
                            keyCondition ?: prefixCondition!!
                        }

                    val condition = (MangaMetaTable.user eq userId) and (MangaMetaTable.ref inList item.mangaIds) and metaKeyCondition

                    deletedMetas +=
                        MangaMetaTable
                            .selectAll()
                            .where { condition }
                            .map { MangaMetaType(it) }

                    MangaMetaTable.deleteWhere { condition }
                    mangaIds += item.mangaIds
                }

                deletedMetas to mangaIds
            }

        val mangas =
            transaction {
                MangaTable
                    .getWithUserData(userId)
                    .selectAll()
                    .where { MangaTable.id inList allMangaIds }
                    .map { MangaType(it) }
                    .distinctBy { it.id }
            }

        return DeleteMangaMetasPayload(clientMutationId, allDeletedMetas, mangas)
    }
}
