package com.azlegend.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.azlegend.wear.AppContainer
import com.azlegend.wear.ui.theme.AzLegendTheme

object Routes {
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val REMOTE = "remote"
    const val ARG_ALBUM = "albumId"
    const val ALBUM = "album/{$ARG_ALBUM}"

    fun album(id: String): String = "album/${Uri.encode(id)}"
}

/**
 * Root of the watch UI. [openPlayerRequests] increments each time the media notification asks the
 * activity to show the player, so the nav host can react to it.
 */
@Composable
fun WearApp(container: AppContainer, openPlayerRequests: Int) {
    AzLegendTheme {
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()

            NotificationPermissionRequest()
            LaunchedEffect(Unit) { container.library.initialize() }
            LaunchedEffect(openPlayerRequests) {
                if (openPlayerRequests > 0) {
                    navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                }
            }

            SwipeDismissableNavHost(navController = navController, startDestination = Routes.LIBRARY) {
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        container = container,
                        onOpenAlbum = { id -> navController.navigate(Routes.album(id)) },
                        onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                        onOpenRemote = { navController.navigate(Routes.REMOTE) { launchSingleTop = true } },
                    )
                }
                composable(Routes.ALBUM) { entry ->
                    val albumId = entry.arguments?.getString(Routes.ARG_ALBUM)?.let(Uri::decode).orEmpty()
                    AlbumScreen(
                        container = container,
                        albumId = albumId,
                        onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                    )
                }
                composable(Routes.PLAYER) {
                    PlayerScreen(container = container)
                }
                composable(Routes.REMOTE) {
                    RemoteScreen(container = container)
                }
            }
        }
    }
}

/** Android 13+ needs runtime consent before download progress and media notifications show. */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
