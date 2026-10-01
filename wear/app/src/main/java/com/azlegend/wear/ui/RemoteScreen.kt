package com.azlegend.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.azlegend.wear.AppConfig
import com.azlegend.wear.AppContainer
import com.azlegend.wear.R
import com.azlegend.wear.remote.NOT_SENT
import com.azlegend.wear.remote.NO_REPLY
import com.azlegend.wear.remote.PairCode
import com.azlegend.wear.remote.RemoteBridge
import com.azlegend.wear.remote.RemoteTrack
import kotlinx.coroutines.delay

/**
 * The desktop music remote: the paired launcher's albums, and a tap switches the song playing over
 * there. Nothing on this screen touches the watch's own player — whatever is loaded here stays
 * exactly as it was.
 */
@Composable
fun RemoteScreen(container: AppContainer) {
    var code by remember { mutableStateOf(container.remote.pairCode) }

    fun save(next: String) {
        container.remote.pairCode = next
        code = next
    }

    if (code.isEmpty()) {
        PairPrompt(onPair = ::save)
    } else {
        RemoteLibrary(code, onUnpair = { save("") })
    }
}

@Composable
private fun PairPrompt(onPair: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val fieldDesc = stringResource(R.string.remote_pair_code)

    ScreenScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.remote_pair_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            BasicTextField(
                value = typed,
                // Typed on a watch keyboard: keep whatever separator was typed out of the way and
                // never hold more than a code's worth.
                onValueChange = { typed = PairCode.normalize(it).take(PairCode.LENGTH) },
                singleLine = true,
                // The default text style is black, which on this screen is invisible.
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                // Supplying onDone replaces the default of closing the keyboard, so a code that is
                // not one yet has to close it explicitly or the keyboard's confirm button appears
                // to do nothing at all. Validity is read from the state here: the keyboard can fire
                // Done before this screen has recomposed with what was typed.
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (PairCode.isValid(typed)) onPair(PairCode.normalize(typed))
                        else defaultKeyboardAction(ImeAction.Done)
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = fieldDesc },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.Center) {
                        if (typed.isEmpty()) {
                            Text(
                                text = stringResource(R.string.remote_pair_placeholder),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        field()
                    }
                },
            )
            Button(
                onClick = { onPair(PairCode.normalize(typed)) },
                enabled = PairCode.isValid(typed),
            ) { Text(stringResource(R.string.remote_pair)) }
        }
    }
}

@Composable
private fun RemoteLibrary(code: String, onUnpair: () -> Unit) {
    val bridge = remember(code) { RemoteBridge(AppConfig.REMOTE_DB_URL, code) }
    // Open only while the app is in front. A stream held through a pocketed, screen-off afternoon is
    // a radio kept awake for a list nobody is reading.
    LifecycleStartEffect(bridge) {
        bridge.start()
        onStopOrDispose { bridge.stop() }
    }

    val connection by bridge.connection.collectAsStateWithLifecycle()
    val hostSeen by bridge.hostSeen.collectAsStateWithLifecycle()
    val library by bridge.library.collectAsStateWithLifecycle()
    val playing by bridge.playing.collectAsStateWithLifecycle()
    val pending by bridge.pending.collectAsStateWithLifecycle()

    // "Not answering" is a claim about the desktop, so it waits for the sync sent on connect to
    // have had a fair chance of being answered.
    var waitedOut by remember { mutableStateOf(false) }
    LaunchedEffect(connection, hostSeen) {
        waitedOut = false
        if (connection == RemoteBridge.Connection.CONNECTED && !hostSeen) {
            delay(6_000)
            waitedOut = true
        }
    }
    LaunchedEffect(pending) {
        if (pending?.error != null) {
            delay(4_000)
            bridge.dismissError()
        }
    }

    // What the desktop last said is only "now playing" while a desktop is there to have said it;
    // otherwise it is whatever was left behind.
    val current = playing.takeIf { hostSeen && it.hasTrack }
    val status = when {
        connection != RemoteBridge.Connection.CONNECTED -> stringResource(R.string.remote_connecting)
        current != null -> current.title ?: current.trackId.orEmpty()
        hostSeen -> stringResource(R.string.remote_idle)
        waitedOut -> stringResource(R.string.remote_no_desktop)
        else -> stringResource(R.string.remote_waiting)
    }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.remote_title)) }
            }
            item {
                Text(
                    text = status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    style = if (current != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    color = if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        IconButton(onClick = { bridge.control("prev") }) {
                            Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.cd_previous))
                        }
                        Spacer(Modifier.width(4.dp))
                        FilledIconButton(
                            onClick = { bridge.control(if (current.isPaused) "resume" else "pause") },
                            modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                        ) {
                            Icon(
                                painter = painterResource(if (current.isPaused) R.drawable.ic_play else R.drawable.ic_pause),
                                contentDescription = stringResource(if (current.isPaused) R.string.cd_play else R.string.cd_pause),
                                modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { bridge.control("next") }) {
                            Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.cd_next))
                        }
                    }
                }
            }
            pending?.error?.let { error ->
                item {
                    Text(
                        text = when (error) {
                            NO_REPLY -> stringResource(R.string.remote_no_reply)
                            NOT_SENT -> stringResource(R.string.remote_not_sent)
                            else -> stringResource(R.string.remote_failed, error)
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (library.isEmpty() && hostSeen) {
                item {
                    Text(
                        text = stringResource(R.string.remote_no_albums),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // No keys: ids come from a database other people can write to, and the list is rebuilt
            // whole whenever the library changes anyway.
            library.forEach { album ->
                item {
                    ListSubHeader(
                        modifier = Modifier.transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                itemsIndexed(album.tracks) { _, track ->
                    RemoteTrackButton(
                        track = track,
                        isCurrent = current?.albumId == album.id && current.trackId == track.id,
                        isWaiting = pending?.let { it.error == null && it.albumId == album.id && it.trackId == track.id } == true,
                        spec = spec,
                    ) { bridge.launch(album.id, track.id) }
                }
            }
            item {
                FilledTonalButton(
                    onClick = onUnpair,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { Icon(painterResource(R.drawable.ic_close), null, Modifier.size(ButtonDefaults.IconSize)) },
                    secondaryLabel = { Text(PairCode.format(code), maxLines = 1) },
                    label = { Text(stringResource(R.string.remote_unpair), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.RemoteTrackButton(
    track: RemoteTrack,
    isCurrent: Boolean,
    isWaiting: Boolean,
    spec: TransformationSpec,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        colors = if (isCurrent) ButtonDefaults.filledVariantButtonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = {
            if (isWaiting) {
                CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 3.dp)
            } else {
                Icon(
                    painterResource(if (isCurrent) R.drawable.ic_music_note else R.drawable.ic_desktop),
                    null,
                    Modifier.size(ButtonDefaults.IconSize),
                )
            }
        },
        secondaryLabel = if (isWaiting) {
            { Text(stringResource(R.string.remote_sending), maxLines = 1) }
        } else {
            null
        },
        label = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}
