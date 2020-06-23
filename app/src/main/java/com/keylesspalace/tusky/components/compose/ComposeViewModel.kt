/* Copyright 2019 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.compose

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.keylesspalace.tusky.adapter.ComposeAutoCompleteAdapter
import com.keylesspalace.tusky.components.compose.ComposeActivity.QueuedMedia
import com.keylesspalace.tusky.components.search.SearchType
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.db.InstanceEntity
import com.keylesspalace.tusky.entity.*
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.service.ServiceClient
import com.keylesspalace.tusky.service.TootToSend
import com.keylesspalace.tusky.util.*
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.Singles
import io.reactivex.schedulers.Schedulers
import retrofit2.Response
import java.util.*
import javax.inject.Inject

/**
 * Throw when trying to add an image when video is already present or the other way around
 */
class VideoOrImageException : Exception()


class ComposeViewModel
@Inject constructor(
        private val api: MastodonApi,
        private val accountManager: AccountManager,
        private val mediaUploader: MediaUploader,
        private val serviceClient: ServiceClient,
        private val saveTootHelper: SaveTootHelper,
        private val db: AppDatabase
) : RxAwareViewModel() {

    private var replyingStatusAuthor: String? = null
    private var replyingStatusContent: String? = null
    internal var startingText: String? = null
    private var savedTootUid: Int = 0
    private var startingContentWarning: String = ""
    private var inReplyToId: String? = null
    private var startingVisibility: Status.Visibility = Status.Visibility.UNKNOWN
    private var contentWarningStateChanged: Boolean = false
    private val instance: MutableLiveData<InstanceEntity?> = MutableLiveData(null)
    private val nodeinfo: MutableLiveData<NodeInfo?> = MutableLiveData(null)
    private val stickers: MutableLiveData<Array<StickerPack>> = MutableLiveData(emptyArray())
    public val haveStickers: MutableLiveData<Boolean> = MutableLiveData(false)
    public var tryFetchStickers = false
    public var formattingSyntax: String = ""
    public var hasNoAttachmentLimits = false

    val instanceParams: LiveData<ComposeInstanceParams> = instance.map { instance ->
        ComposeInstanceParams(
                maxChars = instance?.maximumTootCharacters ?: DEFAULT_CHARACTER_LIMIT,
                pollMaxOptions = instance?.maxPollOptions ?: DEFAULT_MAX_OPTION_COUNT,
                pollMaxLength = instance?.maxPollOptionLength ?: DEFAULT_MAX_OPTION_LENGTH,
                supportsScheduled = instance?.version?.let { VersionUtils(it).supportsScheduledToots() } ?: false
        )
    }
    val instanceMetadata: LiveData<ComposeInstanceMetadata> = nodeinfo.map { nodeinfo ->
        val software = nodeinfo?.software?.name ?: "mastodon"
        
        if(software.equals("pleroma")) {
            hasNoAttachmentLimits = true
            ComposeInstanceMetadata(
                software = "pleroma",
                supportsMarkdown = nodeinfo?.metadata?.postFormats?.contains("text/markdown") ?: false,
                supportsBBcode = nodeinfo?.metadata?.postFormats?.contains("text/bbcode") ?: false,
                supportsHTML = nodeinfo?.metadata?.postFormats?.contains("text/html") ?: false,
                videoLimit = nodeinfo?.metadata?.uploadLimits?.general ?: STATUS_VIDEO_SIZE_LIMIT,
                imageLimit = nodeinfo?.metadata?.uploadLimits?.general ?: STATUS_IMAGE_SIZE_LIMIT
            )
        } else if(software.equals("pixelfed")) {
            ComposeInstanceMetadata(
                software = "pixelfed",
                supportsMarkdown = false,
                supportsBBcode = false,
                supportsHTML = false,
                videoLimit = nodeinfo?.metadata?.config?.uploader?.maxPhotoSize?.let { it * 1024 } ?: STATUS_VIDEO_SIZE_LIMIT,
                imageLimit = nodeinfo?.metadata?.config?.uploader?.maxPhotoSize?.let { it * 1024 } ?: STATUS_IMAGE_SIZE_LIMIT
            )
        } else {
            ComposeInstanceMetadata(
                software = "mastodon",
                supportsMarkdown = nodeinfo?.software?.version?.contains("+glitch") ?: false,
                supportsBBcode = false,
                supportsHTML = nodeinfo?.software?.version?.contains("+glitch") ?: false,
                videoLimit = STATUS_VIDEO_SIZE_LIMIT,
                imageLimit = STATUS_IMAGE_SIZE_LIMIT
            )
        }
    }
    val instanceStickers: LiveData<Array<StickerPack>> = stickers // .map { stickers -> HashMap<String,String>(stickers) }

    val emoji: MutableLiveData<List<Emoji>?> = MutableLiveData()
    val markMediaAsSensitive =
            mutableLiveData(accountManager.activeAccount?.defaultMediaSensitivity ?: false)

    fun toggleMarkSensitive() {
        this.markMediaAsSensitive.value = !this.markMediaAsSensitive.value!!
    }

    val statusVisibility = mutableLiveData(Status.Visibility.UNKNOWN)
    val showContentWarning = mutableLiveData(false)
    val setupComplete = mutableLiveData(false)
    val poll: MutableLiveData<NewPoll?> = mutableLiveData(null)
    val scheduledAt: MutableLiveData<String?> = mutableLiveData(null)
    
    val media = mutableLiveData<List<QueuedMedia>>(listOf())
    val uploadError = MutableLiveData<Throwable>()

    private val mediaToDisposable = mutableMapOf<Long, Disposable>()


    init {
        Singles.zip(api.getCustomEmojis(), api.getInstance()) { emojis, instance ->
            InstanceEntity(
                    instance = accountManager.activeAccount?.domain!!,
                    emojiList = emojis,
                    maximumTootCharacters = instance.maxTootChars,
                    maxPollOptions = instance.pollLimits?.maxOptions,
                    maxPollOptionLength = instance.pollLimits?.maxOptionChars,
                    version = instance.version
            )
        }
                .doOnSuccess {
                    db.instanceDao().insertOrReplace(it)
                }
                .onErrorResumeNext(
                    db.instanceDao().loadMetadataForInstance(accountManager.activeAccount?.domain!!)
                )
                .subscribe ({ instanceEntity ->
                    emoji.postValue(instanceEntity.emojiList)
                    instance.postValue(instanceEntity)
                }, { throwable ->
                    // this can happen on network error when no cached data is available
                    Log.w(TAG, "error loading instance data", throwable)
                })
                .autoDispose()


        api.getNodeinfoLinks().subscribe({
            links -> if(links.links.isNotEmpty()) {
                api.getNodeinfo(links.links[0].href).subscribe({
                    ni -> nodeinfo.postValue(ni)
                }, {
                    err -> Log.d(TAG, "Failed to get nodeinfo", err)
                }).autoDispose()
            }
        }, { err ->
            Log.d(TAG, "Failed to get nodeinfo links", err)
        }).autoDispose()
    }

    fun pickMedia(uri: Uri, filename: String?): LiveData<Either<Throwable, QueuedMedia>> {
        // We are not calling .toLiveData() here because we don't want to stop the process when
        // the Activity goes away temporarily (like on screen rotation).
        val liveData = MutableLiveData<Either<Throwable, QueuedMedia>>()
        val imageLimit = instanceMetadata.value?.videoLimit ?: STATUS_VIDEO_SIZE_LIMIT
        val videoLimit = instanceMetadata.value?.imageLimit ?: STATUS_IMAGE_SIZE_LIMIT
        
        mediaUploader.prepareMedia(uri, videoLimit, imageLimit, filename)
                .map { (type, uri, size) ->
                    val mediaItems = media.value!!
                    if (!hasNoAttachmentLimits
                            && type != QueuedMedia.Type.IMAGE
                            && mediaItems.isNotEmpty()
                            && mediaItems[0].type == QueuedMedia.Type.IMAGE) {
                        throw VideoOrImageException()
                    } else {
                        addMediaToQueue(type, uri, size, filename ?: "unknown")
                    }
                }
                .subscribe({ queuedMedia ->
                    liveData.postValue(Either.Right(queuedMedia))
                }, { error ->
                    liveData.postValue(Either.Left(error))
                })
                .autoDispose()
        return liveData
    }
    
    private fun addMediaToQueue(type: Int, uri: Uri, mediaSize: Long, filename: String): QueuedMedia {
        val mediaItem = QueuedMedia(System.currentTimeMillis(), uri, type, mediaSize, filename,
            hasNoAttachmentLimits)
        val imageLimit = instanceMetadata.value?.videoLimit ?: STATUS_VIDEO_SIZE_LIMIT
        val videoLimit = instanceMetadata.value?.imageLimit ?: STATUS_IMAGE_SIZE_LIMIT        
        
        media.value = media.value!! + mediaItem
        mediaToDisposable[mediaItem.localId] = mediaUploader
                .uploadMedia(mediaItem, videoLimit, imageLimit )
                .subscribe ({ event ->
                    val item = media.value?.find { it.localId == mediaItem.localId }
                            ?: return@subscribe
                    val newMediaItem = when (event) {
                        is UploadEvent.ProgressEvent ->
                            item.copy(uploadPercent = event.percentage)
                        is UploadEvent.FinishedEvent ->
                            item.copy(id = event.attachment.id, uploadPercent = -1)
                    }
                    synchronized(media) {
                        val mediaValue = media.value!!
                        val index = mediaValue.indexOfFirst { it.localId == newMediaItem.localId }
                        media.postValue(if (index == -1) {
                            mediaValue + newMediaItem
                        } else {
                            mediaValue.toMutableList().also { it[index] = newMediaItem }
                        })
                    }
                }, { error ->
                    media.postValue(media.value?.filter { it.localId != mediaItem.localId } ?: emptyList())
                    uploadError.postValue(error)
                })
        return mediaItem
    }

    private fun addUploadedMedia(id: String, type: Int, uri: Uri, description: String?) {
        val mediaItem = QueuedMedia(System.currentTimeMillis(), uri, type, 0, "unknown",
            hasNoAttachmentLimits, -1, id, description)
        media.value = media.value!! + mediaItem
    }

    fun removeMediaFromQueue(item: QueuedMedia) {
        mediaToDisposable[item.localId]?.dispose()
        media.value = media.value!!.withoutFirstWhich { it.localId == item.localId }
    }

    fun didChange(content: String?, contentWarning: String?): Boolean {

        val textChanged = !(content.isNullOrEmpty()
                || startingText?.startsWith(content.toString()) ?: false)

        val contentWarningChanged = showContentWarning.value!!
                && !contentWarning.isNullOrEmpty()
                && !startingContentWarning.startsWith(contentWarning.toString())
        val mediaChanged = !media.value.isNullOrEmpty()
        val pollChanged = poll.value != null

        return textChanged || contentWarningChanged || mediaChanged || pollChanged
    }

    fun contentWarningChanged(value: Boolean) {
        showContentWarning.value = value
        contentWarningStateChanged = true
    }

    fun deleteDraft() {
        saveTootHelper.deleteDraft(this.savedTootUid)
    }

    fun saveDraft(content: String, contentWarning: String) {
        val mediaUris = mutableListOf<String>()
        val mediaDescriptions = mutableListOf<String?>()
        for (item in media.value!!) {
            mediaUris.add(item.uri.toString())
            mediaDescriptions.add(item.description)
        }
        saveTootHelper.saveToot(
                content,
                contentWarning,
                null,
                mediaUris,
                mediaDescriptions,
                savedTootUid,
                inReplyToId,
                replyingStatusContent,
                replyingStatusAuthor,
                statusVisibility.value!!,
                poll.value,
                formattingSyntax
        )
    }

    /**
     * Send status to the server.
     * Uses current state plus provided arguments.
     * @return LiveData which will signal once the screen can be closed or null if there are errors
     */
    fun sendStatus(
            content: String,
            spoilerText: String,
            preview: Boolean
    ): LiveData<Unit> {
        return media
                .filter { items -> items.all { it.uploadPercent == -1 } }
                .map {
                    val mediaIds = ArrayList<String>()
                    val mediaUris = ArrayList<Uri>()
                    val mediaDescriptions = ArrayList<String>()
                    for (item in media.value!!) {
                        mediaIds.add(item.id!!)
                        mediaUris.add(item.uri)
                        mediaDescriptions.add(item.description ?: "")
                    }

                    val tootToSend = TootToSend(
                            content,
                            spoilerText,
                            statusVisibility.value!!.serverString(),
                            mediaUris.isNotEmpty() && (markMediaAsSensitive.value!! || showContentWarning.value!!),
                            mediaIds,
                            mediaUris.map { it.toString() },
                            mediaDescriptions,
                            scheduledAt = scheduledAt.value,
                            inReplyToId = inReplyToId,
                            poll = poll.value,
                            replyingStatusContent = null,
                            replyingStatusAuthorUsername = null,
                            formattingSyntax = formattingSyntax,
                            preview = preview,
                            savedJsonUrls = null,
                            accountId = accountManager.activeAccount!!.id,
                            savedTootUid = 0,
                            idempotencyKey = randomAlphanumericString(16),
                            retries = 0
                    )
                    serviceClient.sendToot(tootToSend)
                }
    }

    fun updateDescription(localId: Long, description: String): LiveData<Boolean> {
        val newList = media.value!!.toMutableList()
        val index = newList.indexOfFirst { it.localId == localId }
        if (index != -1) {
            newList[index] = newList[index].copy(description = description)
        }
        media.value = newList
        val completedCaptioningLiveData = MutableLiveData<Boolean>()
        media.observeForever(object : Observer<List<QueuedMedia>> {
            override fun onChanged(mediaItems: List<QueuedMedia>) {
                val updatedItem = mediaItems.find { it.localId == localId }
                if (updatedItem == null) {
                    media.removeObserver(this)
                } else if (updatedItem.id != null) {
                    api.updateMedia(updatedItem.id, description)
                            .subscribe({
                                completedCaptioningLiveData.postValue(true)
                            }, {
                                completedCaptioningLiveData.postValue(false)
                            })
                            .autoDispose()
                    media.removeObserver(this)
                }
            }
        })
        return completedCaptioningLiveData
    }


    fun searchAutocompleteSuggestions(token: String): List<ComposeAutoCompleteAdapter.AutocompleteResult> {
        when (token[0]) {
            '@' -> {
                return try {
                    api.searchAccounts(query = token.substring(1), limit = 10)
                            .blockingGet()
                            .map { ComposeAutoCompleteAdapter.AccountResult(it) }
                } catch (e: Throwable) {
                    Log.e(TAG, String.format("Autocomplete search for %s failed.", token), e)
                    emptyList()
                }
            }
            '#' -> {
                return try {
                    api.searchObservable(query = token, type = SearchType.Hashtag.apiParameter, limit = 10)
                            .blockingGet()
                            .hashtags
                            .map { ComposeAutoCompleteAdapter.HashtagResult(it) }
                } catch (e: Throwable) {
                    Log.e(TAG, String.format("Autocomplete search for %s failed.", token), e)
                    emptyList()
                }
            }
            ':' -> {
                val emojiList = emoji.value ?: return emptyList()

                val incomplete = token.substring(1).toLowerCase(Locale.ROOT)
                val results = ArrayList<ComposeAutoCompleteAdapter.AutocompleteResult>()
                val resultsInside = ArrayList<ComposeAutoCompleteAdapter.AutocompleteResult>()
                for (emoji in emojiList) {
                    val shortcode = emoji.shortcode.toLowerCase(Locale.ROOT)
                    if (shortcode.startsWith(incomplete)) {
                        results.add(ComposeAutoCompleteAdapter.EmojiResult(emoji))
                    } else if (shortcode.indexOf(incomplete, 1) != -1) {
                        resultsInside.add(ComposeAutoCompleteAdapter.EmojiResult(emoji))
                    }
                }
                if (results.isNotEmpty() && resultsInside.isNotEmpty()) {
                    results.add(ComposeAutoCompleteAdapter.ResultSeparator())
                }
                results.addAll(resultsInside)
                return results
            }
            else -> {
                Log.w(TAG, "Unexpected autocompletion token: $token")
                return emptyList()
            }
        }
    }

    override fun onCleared() {
        for (uploadDisposable in mediaToDisposable.values) {
            uploadDisposable.dispose()
        }
        super.onCleared()
    }

    private fun getStickers() {
        if(!tryFetchStickers)
            return

        api.getStickers().subscribe({ stickers ->
            if (stickers.isNotEmpty()) {
                haveStickers.postValue(true)

                val singles = mutableListOf<Single<Response<StickerPack>>>()

                for(entry in stickers) {
                    val url = entry.value.removePrefix("/").removeSuffix("/") + "/pack.json";
                    singles += api.getStickerPack(url)
                }

                Single.zip(singles) {
                    it.map {
                        it as Response<StickerPack>
                        it.body()!!.internal_url = it.raw().request.url.toString().removeSuffix("pack.json")
                        it.body()!!
                    }
                }.onErrorReturn {
                    Log.d(TAG, "Failed to get sticker pack.json", it)
                    emptyList()
                }.subscribe() { pack ->
                    if(pack.isNotEmpty()) {
                        val array = pack.toTypedArray()
                        array.sort()
                        this.stickers.postValue(array)
                    }
                }.autoDispose()
            }
        }, {
            err -> Log.d(TAG, "Failed to get sticker.json", err)
        }).autoDispose()
    }

    fun setup(composeOptions: ComposeActivity.ComposeOptions?) {
        getStickers() // early as possible
        val preferredVisibility = accountManager.activeAccount!!.defaultPostPrivacy

        val replyVisibility = composeOptions?.replyVisibility ?: Status.Visibility.UNKNOWN
        startingVisibility = Status.Visibility.byNum(
                preferredVisibility.num.coerceAtLeast(replyVisibility.num))

        inReplyToId = composeOptions?.inReplyToId


        val contentWarning = composeOptions?.contentWarning
        if (contentWarning != null) {
            startingContentWarning = contentWarning
        }
        if (!contentWarningStateChanged) {
            showContentWarning.value = !contentWarning.isNullOrBlank()
        }

        // recreate media list
        // when coming from SavedTootActivity
        val loadedDraftMediaUris = composeOptions?.mediaUrls
        val loadedDraftMediaDescriptions: List<String?>? = composeOptions?.mediaDescriptions
        if (loadedDraftMediaUris != null && loadedDraftMediaDescriptions != null) {
            loadedDraftMediaUris.zip(loadedDraftMediaDescriptions)
                    .forEach { (uri, description) ->
                        pickMedia(uri.toUri(), null).observeForever { errorOrItem ->
                            if (errorOrItem.isRight() && description != null) {
                                updateDescription(errorOrItem.asRight().localId, description)
                            }
                        }
                    }
        } else composeOptions?.mediaAttachments?.forEach { a ->
            // when coming from redraft
            val mediaType = when (a.type) {
                Attachment.Type.VIDEO, Attachment.Type.GIFV -> QueuedMedia.Type.VIDEO
                Attachment.Type.UNKNOWN, Attachment.Type.IMAGE -> QueuedMedia.Type.IMAGE
                Attachment.Type.AUDIO -> QueuedMedia.Type.AUDIO
            }
            addUploadedMedia(a.id, mediaType, a.url.toUri(), a.description)
        }


        savedTootUid = composeOptions?.savedTootUid ?: 0
        startingText = composeOptions?.tootText


        val tootVisibility = composeOptions?.visibility ?: Status.Visibility.UNKNOWN
        if (tootVisibility.num != Status.Visibility.UNKNOWN.num) {
            startingVisibility = tootVisibility
        }
        statusVisibility.value = startingVisibility
        val mentionedUsernames = composeOptions?.mentionedUsernames
        if (mentionedUsernames != null) {
            val builder = StringBuilder()
            for (name in mentionedUsernames) {
                builder.append('@')
                builder.append(name)
                builder.append(' ')
            }
            startingText = builder.toString()
        }


        scheduledAt.value = composeOptions?.scheduledAt

        composeOptions?.sensitive?.let { markMediaAsSensitive.value = it }

        val poll = composeOptions?.poll
        if (poll != null && composeOptions.mediaAttachments.isNullOrEmpty()) {
            this.poll.value = poll
        }
        replyingStatusContent = composeOptions?.replyingStatusContent
        replyingStatusAuthor = composeOptions?.replyingStatusAuthor
        
        formattingSyntax = composeOptions?.formattingSyntax ?: accountManager.activeAccount!!.defaultFormattingSyntax
    }

    fun updatePoll(newPoll: NewPoll) {
        poll.value = newPoll
    }

    fun updateScheduledAt(newScheduledAt: String?) {
        scheduledAt.value = newScheduledAt
    }

    private companion object {
        const val TAG = "ComposeViewModel"
    }

}

fun <T> mutableLiveData(default: T) = MutableLiveData<T>().apply { value = default }

const val DEFAULT_CHARACTER_LIMIT = 500
private const val DEFAULT_MAX_OPTION_COUNT = 4
private const val DEFAULT_MAX_OPTION_LENGTH = 25
private const val STATUS_VIDEO_SIZE_LIMIT : Long = 41943040 // 40MiB
private const val STATUS_IMAGE_SIZE_LIMIT : Long = 8388608 // 8MiB
                

data class ComposeInstanceParams(
        val maxChars: Int,
        val pollMaxOptions: Int,
        val pollMaxLength: Int,
        val supportsScheduled: Boolean
)

data class ComposeInstanceMetadata(
        val software: String,
        val supportsMarkdown: Boolean,
        val supportsBBcode: Boolean,
        val supportsHTML: Boolean,
        val videoLimit: Long,
        val imageLimit: Long
)
