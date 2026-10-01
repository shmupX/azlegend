package com.azlegend.wear

import android.content.Context
import com.azlegend.wear.data.CatalogRepository
import com.azlegend.wear.data.Downloads
import com.azlegend.wear.data.HighBandwidthNetwork
import com.azlegend.wear.data.LibraryRepository
import com.azlegend.wear.data.MusicStore
import com.azlegend.wear.playback.PlaybackStateStore
import com.azlegend.wear.playback.PlayerConnection
import com.azlegend.wear.playback.toMediaItem
import com.azlegend.wear.remote.RemoteStore

/** Hand-rolled dependency container; small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val store = MusicStore(appContext)
    val catalogRepository = CatalogRepository(appContext, store, AppConfig.BASE_URL)

    /** Used by the UI to describe the current connection; downloads acquire their own network. */
    val connectivity = HighBandwidthNetwork(appContext)

    val library = LibraryRepository(store, catalogRepository, isOnline = connectivity::hasInternet)
    val playbackState = PlaybackStateStore(appContext)

    /** Which desktop launcher the music remote is paired with. */
    val remote = RemoteStore(appContext)
    val player = PlayerConnection(appContext)
    val downloads = Downloads(appContext, library, store) { albumId ->
        player.refreshQueue(albumId) { id -> library.track(id)?.toMediaItem() }
    }
}
