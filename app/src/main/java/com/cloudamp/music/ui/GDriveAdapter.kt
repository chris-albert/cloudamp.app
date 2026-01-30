package com.cloudamp.music.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudamp.music.R
import com.cloudamp.music.api.DriveFile

sealed class GDriveItem {
    data class FolderItem(val file: DriveFile) : GDriveItem()
    data class AudioItem(val file: DriveFile) : GDriveItem()
    data class BackItem(val parentId: String?) : GDriveItem()
}

class GDriveAdapter(
    private val onFolderClick: (DriveFile) -> Unit,
    private val onAudioClick: (DriveFile, List<DriveFile>, Int) -> Unit,
    private val onBackClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<GDriveItem>()
    private var showBackItem = false
    private var allFiles: List<DriveFile> = emptyList()
    private var hasParentStored = false
    private var currentFilter: String = ""

    companion object {
        private const val TYPE_BACK = 0
        private const val TYPE_FOLDER = 1
        private const val TYPE_AUDIO = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GDriveItem.BackItem -> TYPE_BACK
            is GDriveItem.FolderItem -> TYPE_FOLDER
            is GDriveItem.AudioItem -> TYPE_AUDIO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BACK -> BackViewHolder(inflater.inflate(R.layout.item_gdrive_folder, parent, false))
            TYPE_FOLDER -> FolderViewHolder(inflater.inflate(R.layout.item_gdrive_folder, parent, false))
            TYPE_AUDIO -> AudioViewHolder(inflater.inflate(R.layout.item_gdrive_audio, parent, false))
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GDriveItem.BackItem -> (holder as BackViewHolder).bind()
            is GDriveItem.FolderItem -> (holder as FolderViewHolder).bind(item)
            is GDriveItem.AudioItem -> (holder as AudioViewHolder).bind(item)
        }
    }

    override fun getItemCount() = items.size

    fun setFiles(files: List<DriveFile>, hasParent: Boolean) {
        allFiles = files
        hasParentStored = hasParent
        currentFilter = ""
        rebuildItems(files, hasParent)
    }

    private fun rebuildItems(files: List<DriveFile>, hasParent: Boolean) {
        items.clear()
        showBackItem = hasParent

        if (hasParent) {
            items.add(GDriveItem.BackItem(null))
        }

        // Separate folders and audio files, folders first
        val folders = files.filter { it.isFolder() }.sortedBy { it.name.lowercase() }
        val audioFiles = files.filter { it.isAudioFile() }.sortedBy { it.name.lowercase() }

        items.addAll(folders.map { GDriveItem.FolderItem(it) })
        items.addAll(audioFiles.map { GDriveItem.AudioItem(it) })

        notifyDataSetChanged()
    }

    fun filterFiles(query: String) {
        currentFilter = query
        if (query.isEmpty()) {
            rebuildItems(allFiles, hasParentStored)
            return
        }
        val filtered = allFiles.filter { fuzzyMatch(it.name, query) }
        rebuildItems(filtered, hasParentStored)
    }

    fun clearFilter() {
        currentFilter = ""
        rebuildItems(allFiles, hasParentStored)
    }

    private fun fuzzyMatch(text: String, query: String): Boolean {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        // Substring match first (most intuitive)
        if (lowerText.contains(lowerQuery)) return true

        // Fuzzy: all query chars appear in order in the text
        var textIndex = 0
        for (queryChar in lowerQuery) {
            val found = lowerText.indexOf(queryChar, textIndex)
            if (found == -1) return false
            textIndex = found + 1
        }
        return true
    }

    private fun getAllAudioFiles(): List<DriveFile> {
        return items.filterIsInstance<GDriveItem.AudioItem>().map { it.file }
    }

    inner class BackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.folderNameTextView)

        fun bind() {
            nameTextView.text = ".. (Back)"
            itemView.setOnClickListener {
                onBackClick()
            }
        }
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.folderNameTextView)

        fun bind(item: GDriveItem.FolderItem) {
            nameTextView.text = item.file.name
            itemView.setOnClickListener {
                onFolderClick(item.file)
            }
        }
    }

    inner class AudioViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.audioNameTextView)
        private val sizeTextView: TextView = itemView.findViewById(R.id.audioSizeTextView)
        private val typeTextView: TextView = itemView.findViewById(R.id.audioTypeTextView)

        fun bind(item: GDriveItem.AudioItem) {
            nameTextView.text = item.file.name
            sizeTextView.text = item.file.getFileSizeFormatted()
            typeTextView.text = item.file.getFileExtension()

            itemView.setOnClickListener {
                val audioFiles = getAllAudioFiles()
                val position = audioFiles.indexOf(item.file)
                onAudioClick(item.file, audioFiles, position)
            }
        }
    }
}
