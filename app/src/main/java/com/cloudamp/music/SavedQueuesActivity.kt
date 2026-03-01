package com.cloudamp.music

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.cache.SavedQueuesManager
import com.cloudamp.music.models.SavedQueue
import com.cloudamp.music.playback.CloudAmpService
import com.cloudamp.music.playback.GDrivePlaybackManager
import com.cloudamp.music.playback.JellyfinPlaybackManager
import com.cloudamp.music.ui.SavedQueuesAdapter
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*

class SavedQueuesActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var savedQueuesManager: SavedQueuesManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SavedQueuesAdapter
    private lateinit var emptyContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_queues)

        savedQueuesManager = SavedQueuesManager.getInstance(this)

        setupDrawer()
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        // Persist live playback position so the list shows the current track
        savedQueuesManager.saveActiveQueuePosition()
        loadSavedQueues()
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
        navigationView.setCheckedItem(R.id.nav_saved_queues)
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.savedQueuesRecyclerView)
        emptyContainer = findViewById(R.id.emptyContainer)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = SavedQueuesAdapter(
            onQueueClick = { queue -> loadQueue(queue) },
            onRenameClick = { queue -> showRenameDialog(queue) },
            onDeleteClick = { queue -> showDeleteConfirmation(queue) }
        )
        recyclerView.adapter = adapter
    }

    private fun loadSavedQueues() {
        val queues = savedQueuesManager.getSavedQueues()
        adapter.setQueues(queues)

        if (queues.isEmpty()) {
            emptyContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun loadQueue(queue: SavedQueue) {
        when (queue.provider) {
            SavedQueue.PROVIDER_GDRIVE -> loadGDriveQueue(queue)
            SavedQueue.PROVIDER_JELLYFIN -> loadJellyfinQueue(queue)
        }
    }

    private fun loadGDriveQueue(queue: SavedQueue) {
        if (queue.driveFiles.isEmpty()) {
            Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        // Save position of the currently active queue before switching
        savedQueuesManager.saveActiveQueuePosition()

        val gdrive = GDrivePlaybackManager.getInstance(this)
        CloudAmpService.ensureForeground(this)
        gdrive.playFiles(queue.driveFiles, queue.currentIndex)

        // Seek to saved position within the track after buffering
        if (queue.currentPositionMs > 0) {
            scope.launch {
                delay(1500)
                gdrive.seekTo(queue.currentPositionMs)
            }
        }

        // Mark this queue as active and update last played time
        savedQueuesManager.setActiveQueue(queue.id)
        savedQueuesManager.updateQueuePosition(
            queue.id, queue.currentIndex, queue.currentPositionMs
        )

        Toast.makeText(
            this,
            "Loading: ${queue.name} (${queue.getTrackCount()} tracks)",
            Toast.LENGTH_SHORT
        ).show()

        // Open now playing
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun loadJellyfinQueue(queue: SavedQueue) {
        val items = queue.jellyfinItems.orEmpty()
        if (items.isEmpty()) {
            Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show()
            return
        }

        // Save position of the currently active queue before switching
        savedQueuesManager.saveActiveQueuePosition()

        val jellyfin = JellyfinPlaybackManager.getInstance(this)
        CloudAmpService.ensureForeground(this)
        jellyfin.playItems(items, queue.currentIndex)

        // Seek to saved position within the track after buffering
        if (queue.currentPositionMs > 0) {
            scope.launch {
                delay(1500)
                jellyfin.seekTo(queue.currentPositionMs)
            }
        }

        // Mark this queue as active and update last played time
        savedQueuesManager.setActiveQueue(queue.id)
        savedQueuesManager.updateQueuePosition(
            queue.id, queue.currentIndex, queue.currentPositionMs
        )

        Toast.makeText(
            this,
            "Loading: ${queue.name} (${queue.getTrackCount()} tracks)",
            Toast.LENGTH_SHORT
        ).show()

        // Open now playing
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    private fun showRenameDialog(queue: SavedQueue) {
        val editText = EditText(this).apply {
            setText(queue.name)
            setTextColor(resources.getColor(R.color.winamp_text, null))
            setBackgroundColor(resources.getColor(R.color.winamp_background, null))
            setPadding(48, 32, 48, 32)
            setSelection(text.length)
        }

        AlertDialog.Builder(this, R.style.Theme_CloudAmp_Dialog)
            .setTitle("Rename Queue")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    savedQueuesManager.renameQueue(queue.id, newName)
                    loadSavedQueues()
                    Toast.makeText(this, "Renamed to: $newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(queue: SavedQueue) {
        AlertDialog.Builder(this, R.style.Theme_CloudAmp_Dialog)
            .setTitle("Delete Queue")
            .setMessage("Delete \"${queue.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                savedQueuesManager.deleteQueue(queue.id)
                loadSavedQueues()
                Toast.makeText(this, "Deleted: ${queue.name}", Toast.LENGTH_SHORT).show()
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
            R.id.nav_jellyfin_home -> {
                startActivity(Intent(this, JellyfinHomeActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_library -> {
                startActivity(Intent(this, JellyfinLibraryActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_playlists -> {
                startActivity(Intent(this, JellyfinPlaylistsActivity::class.java))
                finish()
            }
            R.id.nav_jellyfin_recent_played -> {
                val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, JellyfinRecentAlbumsActivity.MODE_RECENTLY_PLAYED)
                startActivity(intent)
                finish()
            }
            R.id.nav_jellyfin_recent_added -> {
                val intent = Intent(this, JellyfinRecentAlbumsActivity::class.java)
                intent.putExtra(JellyfinRecentAlbumsActivity.EXTRA_MODE, JellyfinRecentAlbumsActivity.MODE_RECENTLY_ADDED)
                startActivity(intent)
                finish()
            }
            R.id.nav_saved_queues -> {
                // Already here
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
