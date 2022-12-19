package com.github.libretube.util

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.libretube.R
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainThread
import com.github.libretube.obj.ImportPlaylist
import com.github.libretube.obj.ImportPlaylistFile
import com.github.libretube.obj.NewPipeSubscription
import com.github.libretube.obj.NewPipeSubscriptions
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ImportHelper(
    private val activity: Activity
) {
    /**
     * Import subscriptions by a file uri
     */
    fun importSubscriptions(uri: Uri?) {
        if (uri == null) return
        try {
            val applicationContext = activity.applicationContext
            val channels = getChannelsFromUri(uri)
            CoroutineScope(Dispatchers.IO).launch {
                SubscriptionHelper.importSubscriptions(channels)
            }.invokeOnCompletion {
                applicationContext.toastFromMainThread(R.string.importsuccess)
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG(), e.toString())
            activity.toastFromMainThread(
                activity.getString(R.string.unsupported_file_format) +
                    " (${activity.contentResolver.getType(uri)})"
            )
        } catch (e: Exception) {
            Log.e(TAG(), e.toString())
            Toast.makeText(activity, e.localizedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Get a list of channel IDs from a file [Uri]
     */
    private fun getChannelsFromUri(uri: Uri): List<String> {
        return when (val fileType = activity.contentResolver.getType(uri)) {
            "application/json", "application/*", "application/octet-stream" -> {
                // NewPipe subscriptions format
                val subscriptions = ObjectMapper().readValue(
                    uri.readText(),
                    NewPipeSubscriptions::class.java
                )
                subscriptions.subscriptions.orEmpty().map {
                    it.url!!.replace("https://www.youtube.com/channel/", "")
                }
            }
            "text/csv", "text/comma-separated-values" -> {
                // import subscriptions from Google/YouTube Takeout
                activity.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().useLines { lines ->
                        lines.map { line -> line.substringBefore(",") }
                            .filter { channelId -> channelId.length == 24 }
                            .toList()
                    }
                }.orEmpty()
            }
            else -> throw IllegalArgumentException("Unsupported file type: $fileType")
        }
    }

    /**
     * Write the text to the document
     */
    fun exportSubscriptions(uri: Uri?) {
        if (uri == null) return
        runBlocking {
            val subs = if (PreferenceHelper.getToken() != "") {
                RetrofitInstance.authApi.subscriptions(PreferenceHelper.getToken())
            } else {
                RetrofitInstance.authApi.unauthenticatedSubscriptions(
                    SubscriptionHelper.getFormattedLocalSubscriptions()
                )
            }
            val newPipeChannels = mutableListOf<NewPipeSubscription>()
            subs.forEach {
                newPipeChannels += NewPipeSubscription(
                    name = it.name,
                    service_id = 0,
                    url = "https://www.youtube.com" + it.url
                )
            }

            val newPipeSubscriptions = NewPipeSubscriptions(
                subscriptions = newPipeChannels
            )

            uri.write(newPipeSubscriptions)

            activity.toastFromMainThread(R.string.exportsuccess)
        }
    }

    /**
     * Import Playlists
     */
    fun importPlaylists(uri: Uri?) {
        if (uri == null) return

        val importPlaylists = mutableListOf<ImportPlaylist>()

        when (val fileType = activity.contentResolver.getType(uri)) {
            "text/csv", "text/comma-separated-values" -> {
                val playlist = ImportPlaylist()
                activity.contentResolver.openInputStream(uri)?.use {
                    val lines = it.bufferedReader().readLines()
                    playlist.name = lines[1].split(",").reversed()[2]
                    val splitIndex = lines.indexOfFirst { line -> line.isBlank() }
                    lines.subList(splitIndex + 2, lines.size).forEach { line ->
                        line.split(",").firstOrNull()?.let { videoId ->
                            if (videoId.isNotBlank()) playlist.videos = playlist.videos + videoId
                        }
                    }
                    importPlaylists.add(playlist)
                }
            }
            "application/json", "application/*", "application/octet-stream" -> {
                val playlistFile = ObjectMapper().readValue(
                    uri.readText(),
                    ImportPlaylistFile::class.java
                )
                importPlaylists.addAll(playlistFile.playlists.orEmpty())
            }
            else -> {
                activity.applicationContext.toastFromMainThread("Unsupported file type $fileType")
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                PlaylistsHelper.importPlaylists(activity, importPlaylists)
                activity.applicationContext.toastFromMainThread(R.string.success)
            } catch (e: Exception) {
                Log.e(TAG(), e.toString())
                e.localizedMessage?.let {
                    activity.applicationContext.toastFromMainThread(it)
                }
            }
        }
    }

    /**
     * Export Playlists
     */
    fun exportPlaylists(uri: Uri?) {
        if (uri == null) return

        runBlocking {
            val playlists = PlaylistsHelper.exportPlaylists()
            val playlistFile = ImportPlaylistFile(
                format = "Piped",
                version = 1,
                playlists = playlists
            )

            uri.write(playlistFile)

            activity.toastFromMainThread(R.string.exportsuccess)
        }
    }

    private fun Uri.readText(): String {
        return activity.contentResolver.openInputStream(this)?.use {
            it.bufferedReader().use { reader -> reader.readText() }
        }.orEmpty()
    }

    private fun Uri.write(text: Any) {
        activity.contentResolver.openFileDescriptor(this, "w")?.use {
            FileOutputStream(it.fileDescriptor).use { fileOutputStream ->
                fileOutputStream.write(
                    ObjectMapper().writeValueAsBytes(text)
                )
            }
        }
    }
}
