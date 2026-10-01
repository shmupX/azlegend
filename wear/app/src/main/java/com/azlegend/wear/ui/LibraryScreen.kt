package com.azlegend.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.azlegend.wear.AppContainer
import com.azlegend.wear.R
import com.azlegend.wear.data.Album
import com.azlegend.wear.data.DownloadProgress
import com.azlegend.wear.data.SyncStatus
import com.azlegend.wear.data.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenAlbum: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val albums by container.library.albums.collectAsStateWithLifecycle()
    val initialized by container.library.initialized.collectAsStateWithLifecycle()
    val sync by container.library.syncStatus.collectAsStateWithLifecycle()
    val progress by container.downloads.progress.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Files pushed with adb while the app was in the background show up on the next resume, and so
    // do songs added on the site since the last sync (throttled and silent; see autoRefreshCatalog).
    LifecycleResumeEffect(Unit) {
        scope.launch { container.library.rebuild() }
        container.library.autoRefreshCatalog()
        onPauseOrDispose { }
    }

    var storageBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(albums) {
        storageBytes = withContext(Dispatchers.IO) { container.library.totalDownloadedBytes() }
    }
    val networkLabel = remember(albums, sync) { container.connectivity.describeActiveNetwork() }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val remoteAlbums = albums.filter { !it.isLocal }
    val localAlbums = albums.filter { it.isLocal }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.library_title)) }
            }
            if (player.hasMedia && player.title.isNotBlank()) {
                item {
                    Button(
                        onClick = onOpenPlayer,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        icon = { Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(ButtonDefaults.IconSize)) },
                        secondaryLabel = { Text(stringResource(R.string.now_playing), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        label = { Text(player.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            items(remoteAlbums, key = { it.id }) { album ->
                AlbumButton(album, progress[album.id], spec) { onOpenAlbum(album.id) }
            }
            if (localAlbums.isNotEmpty()) {
                item {
                    ListSubHeader(
                        modifier = Modifier.transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) { Text(stringResource(R.string.library_local_files)) }
                }
                items(localAlbums, key = { it.id }) { album ->
                    AlbumButton(album, null, spec) { onOpenAlbum(album.id) }
                }
            }
            if (initialized && albums.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.library_empty),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = onOpenRemote,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { Icon(painterResource(R.drawable.ic_desktop), null, Modifier.size(ButtonDefaults.IconSize)) },
                    secondaryLabel = { Text(stringResource(R.string.remote_entry_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    label = { Text(stringResource(R.string.remote_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                SyncButton(sync, spec) { container.library.refreshCatalog() }
            }
            item {
                val storage = stringResource(R.string.storage_used, formatBytes(storageBytes))
                val network = networkLabel?.let { stringResource(R.string.network_via, it) }
                    ?: stringResource(R.string.network_offline)
                Text(
                    text = "$storage\n$network",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.AlbumButton(
    album: Album,
    progress: DownloadProgress?,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    val downloading = progress?.isActive == true
    val secondary = when {
        downloading && progress.trackCount > 0 ->
            stringResource(R.string.album_downloading, progress.trackIndex + 1, progress.trackCount, progress.percent)
        downloading -> stringResource(R.string.album_queued)
        album.isLocal -> pluralStringResource(R.plurals.album_tracks, album.tracks.size, album.tracks.size)
        album.isFullyDownloaded -> stringResource(R.string.album_downloaded_all, formatBytes(album.downloadedBytes))
        album.downloadedCount > 0 -> stringResource(R.string.album_downloaded_of, album.downloadedCount, album.tracks.size)
        else -> pluralStringResource(R.plurals.album_tracks, album.tracks.size, album.tracks.size)
    }
    val iconRes = when {
        album.isLocal -> R.drawable.ic_folder
        album.isFullyDownloaded -> R.drawable.ic_check_circle
        album.downloadedCount > 0 -> R.drawable.ic_download
        else -> R.drawable.ic_cloud
    }
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        icon = {
            if (downloading) {
                CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 3.dp)
            } else {
                Icon(painterResource(iconRes), null, Modifier.size(ButtonDefaults.IconSize))
            }
        },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun TransformingLazyColumnItemScope.SyncButton(
    status: SyncStatus,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    val syncing = status is SyncStatus.Syncing
    FilledTonalButton(
        onClick = onClick,
        enabled = !syncing,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        icon = {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 3.dp)
            } else {
                Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(ButtonDefaults.IconSize))
            }
        },
        secondaryLabel = when {
            status is SyncStatus.Failed ->
                { { Text(stringResource(R.string.sync_failed, status.message), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            // A dead endpoint would otherwise read as a clean "Synced" while new songs never arrive.
            status is SyncStatus.Synced && status.staleAlbums.isNotEmpty() ->
                { { Text(pluralStringResource(R.plurals.sync_partial, status.staleAlbums.size, status.staleAlbums.size), maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            else -> null
        },
        label = {
            Text(stringResource(if (syncing) R.string.syncing else R.string.sync_catalog), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
    )
}
