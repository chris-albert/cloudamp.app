package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.cache.PlaylistsCore.Playlist
import com.cloudamp.music.cache.PlaylistsCore.PlaylistOp
import com.cloudamp.music.cache.PlaylistsRepository
import com.cloudamp.music.ui.MiniPlayerBar
import com.cloudamp.music.ui.PlaylistDialogs
import com.cloudamp.music.ui.PlaylistsAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistsActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var repository: PlaylistsRepository
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PlaylistsAdapter
    private lateinit var emptyContainer: LinearLayout
    private lateinit var miniPlayerBar: MiniPlayerBar
    private lateinit var appliedThemeId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedThemeId = ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)

        repository = PlaylistsRepository.getInstance(this)

        setupDrawer()
        setupRecyclerView()

        miniPlayerBar = MiniPlayerBar(this, scope)
        miniPlayerBar.attach()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.recreateIfThemeChanged(this, appliedThemeId)
        // Cached list renders immediately; the Drive read then reconciles
        // (pushing any queued edits from a previous session).
        showPlaylists()
        syncFromDrive()
        miniPlayerBar.startUpdates()
    }

    override fun onPause() {
        miniPlayerBar.stopUpdates()
        super.onPause()
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        toggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener(this)
        navigationView.setCheckedItem(R.id.nav_playlists)
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.playlistsRecyclerView)
        emptyContainer = findViewById(R.id.emptyContainer)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = PlaylistsAdapter(
            onPlaylistClick = { playlist -> openPlaylist(playlist) },
            onRenameClick = { playlist -> showRenameDialog(playlist) },
            onDeleteClick = { playlist -> showDeleteConfirmation(playlist) }
        )
        recyclerView.adapter = adapter
    }

    private fun showPlaylists() {
        val playlists = repository.listPlaylists()
        adapter.setPlaylists(playlists)
        emptyContainer.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (playlists.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun syncFromDrive() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.sync() }
                showPlaylists()
            } catch (e: Exception) {
                // Offline or root not configured: the cached list stays; edits retry next time
            }
        }
    }

    private fun openPlaylist(playlist: Playlist) {
        startActivity(
            Intent(this, PlaylistDetailActivity::class.java)
                .putExtra(PlaylistDetailActivity.EXTRA_PLAYLIST_ID, playlist.id)
        )
    }

    private fun showCreateDialog() {
        PlaylistDialogs.promptName(this, "New Playlist", "Create") { name ->
            val playlist = repository.createPlaylist(name)
            repository.syncInBackground()
            showPlaylists()
            openPlaylist(playlist)
        }
    }

    private fun showRenameDialog(playlist: Playlist) {
        PlaylistDialogs.promptName(this, "Rename Playlist", "Rename", playlist.name) { name ->
            repository.apply(PlaylistOp.Rename(playlist.id, name, repository.now()))
            repository.syncInBackground()
            showPlaylists()
        }
    }

    private fun showDeleteConfirmation(playlist: Playlist) {
        AlertDialog.Builder(this, R.style.Theme_CloudAmp_Dialog)
            .setTitle("Delete Playlist")
            .setMessage("Delete \"${playlist.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                repository.apply(PlaylistOp.Delete(playlist.id))
                repository.syncInBackground()
                showPlaylists()
                Toast.makeText(this, "Deleted: ${playlist.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_gdrive_library -> {
                startActivity(Intent(this, GDriveLibraryActivity::class.java))
                finish()
            }
            R.id.nav_gdrive_music_library -> {
                startActivity(Intent(this, GDriveStructuredLibraryActivity::class.java))
                finish()
            }
            R.id.nav_gdrive_home -> {
                startActivity(Intent(this, GDriveHomeActivity::class.java))
                finish()
            }
            R.id.nav_playlists -> {
                // Already here
            }
            R.id.nav_cached -> {
                startActivity(Intent(this, CachedAlbumsActivity::class.java))
                finish()
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return when (item.itemId) {
            R.id.action_new_playlist -> {
                showCreateDialog()
                true
            }
            R.id.action_now_playing -> {
                startActivity(Intent(this, NowPlayingActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.action_search)?.isVisible = false
        menu.findItem(R.id.action_new_playlist)?.isVisible = true
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
