package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.cache.PlaylistsCore.PlaylistOp
import com.cloudamp.music.cache.PlaylistsCore.PlaylistTrack
import com.cloudamp.music.cache.PlaylistsRepository
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.ui.MiniPlayerBar
import com.cloudamp.music.ui.PlaylistTracksAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
    }

    private lateinit var repository: PlaylistsRepository
    private lateinit var playlistId: String
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var headerTextView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PlaylistTracksAdapter
    private lateinit var emptyContainer: LinearLayout
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var miniPlayerBar: MiniPlayerBar
    private lateinit var appliedThemeId: String

    // Working copy of the playlist's tracks; kept in sync with drag reorders
    private val tracks = mutableListOf<PlaylistTrack>()

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeId = ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)

        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID) ?: run { finish(); return }
        repository = PlaylistsRepository.getInstance(this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "PLAYLIST"

        headerTextView = findViewById(R.id.playlistHeaderTextView)
        emptyContainer = findViewById(R.id.emptyContainer)
        findViewById<Button>(R.id.playButton).setOnClickListener { play(0, shuffle = false) }
        findViewById<Button>(R.id.shuffleButton).setOnClickListener { play(0, shuffle = true) }

        setupRecyclerView()

        miniPlayerBar = MiniPlayerBar(this, scope)
        miniPlayerBar.attach()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.recreateIfThemeChanged(this, appliedThemeId)
        if (showPlaylist()) syncFromDrive()
        miniPlayerBar.startUpdates()
    }

    override fun onPause() {
        miniPlayerBar.stopUpdates()
        super.onPause()
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.tracksRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = PlaylistTracksAdapter(
            onTrackClick = { index -> play(index, shuffle = false) },
            onRemoveClick = { index -> removeTrack(index) },
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }
        )
        recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                tracks.add(to, tracks.removeAt(from))
                adapter.moveTrack(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Drop finished: persist the new order and refresh position numbers
                repository.apply(PlaylistOp.SetOrder(playlistId, tracks.map { it.fileId }, repository.now()))
                repository.syncInBackground()
                adapter.notifyDataSetChanged()
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    /** Render from the local repository state. Returns false (and finishes) if the playlist is gone. */
    private fun showPlaylist(): Boolean {
        val playlist = repository.getPlaylist(playlistId) ?: run {
            // Deleted on this or another device while we were open
            finish()
            return false
        }
        tracks.clear()
        tracks.addAll(playlist.tracks)

        val count = tracks.size
        headerTextView.text = "▶ ${playlist.name.uppercase()} ($count track${if (count != 1) "s" else ""})"

        // Resolve display metadata (library cache when available, filename otherwise)
        val gdrive = GDrivePlaybackManager.getInstance(this)
        adapter.setTracks(tracks.map { gdrive.driveFileToTrack(it.toDriveFile()) })

        emptyContainer.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
        return true
    }

    private fun syncFromDrive() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.sync() }
                showPlaylist()
            } catch (e: Exception) {
                // Offline or root not configured: the cached playlist stays; edits retry next time
            }
        }
    }

    private fun removeTrack(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        repository.apply(PlaylistOp.RemoveTrack(playlistId, track.fileId, repository.now()))
        repository.syncInBackground()
        showPlaylist()
    }

    private fun play(startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val files = tracks.map { it.toDriveFile() }
        val queue = if (shuffle) files.shuffled() else files
        val index = if (shuffle) 0 else startIndex.coerceIn(queue.indices)

        val gdrive = GDrivePlaybackManager.getInstance(this)
        CloudAmpService.ensureForeground(this)
        gdrive.playFiles(queue, index)

        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
