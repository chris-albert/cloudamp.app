package com.cloudamp.music.util

data class ParsedTrack(
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val title: String
)

data class ParsedAlbum(
    val name: String,
    val year: Int? = null
)

object MusicFilenameParser {

    /**
     * Parse a track filename like:
     * - "01 - Song Title.flac"
     * - "01. Song Title.mp3"
     * - "1-05 Song Title.ogg" (disc 1 track 5)
     * - "Artist - Album - 03 - Song Title.mp3"
     * - "Song Title.mp3" (no number)
     */
    fun parseTrackFilename(filename: String): ParsedTrack {
        val nameWithoutExt = filename.substringBeforeLast('.')

        // Pattern: disc-track separator (e.g. "1-05 Title" or "1-05. Title" or "1-05 - Title")
        val discTrackRegex = Regex("""^(\d{1,2})-(\d{1,3})\s*[.\-]?\s*(.+)$""")
        discTrackRegex.find(nameWithoutExt)?.let { match ->
            val disc = match.groupValues[1].toIntOrNull()
            val track = match.groupValues[2].toIntOrNull()
            val title = match.groupValues[3].trim()
            if (title.isNotEmpty()) {
                return ParsedTrack(trackNumber = track, discNumber = disc, title = title)
            }
        }

        // Pattern: "01 - Title" or "01. Title" or "01 Title"
        val trackRegex = Regex("""^(\d{1,3})\s*[.\-]?\s+(.+)$""")
        trackRegex.find(nameWithoutExt)?.let { match ->
            val track = match.groupValues[1].toIntOrNull()
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty()) {
                return ParsedTrack(trackNumber = track, title = title)
            }
        }

        // Pattern: track number after prefix — "Artist - Album - 03 - Title"
        val midTrackRegex = Regex("""^.+\s-\s(\d{1,3})\s*[.\-]\s*(.+)$""")
        midTrackRegex.find(nameWithoutExt)?.let { match ->
            val track = match.groupValues[1].toIntOrNull()
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty()) {
                return ParsedTrack(trackNumber = track, title = title)
            }
        }

        // No track number found
        return ParsedTrack(title = nameWithoutExt.trim())
    }

    /**
     * Parse an album folder name like:
     * - "(2020) Album Name"
     * - "Album Name (2020)"
     * - "2020 - Album Name"
     * - "Album Name"
     */
    fun parseAlbumFolderName(folderName: String): ParsedAlbum {
        // Pattern: "(2020) Album Name"
        val prefixParenRegex = Regex("""^\((\d{4})\)\s*(.+)$""")
        prefixParenRegex.find(folderName)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val name = match.groupValues[2].trim()
            if (name.isNotEmpty()) return ParsedAlbum(name = name, year = year)
        }

        // Pattern: "[2020] Album Name"
        val prefixBracketRegex = Regex("""^\[(\d{4})]\s*(.+)$""")
        prefixBracketRegex.find(folderName)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val name = match.groupValues[2].trim()
            if (name.isNotEmpty()) return ParsedAlbum(name = name, year = year)
        }

        // Pattern: "2020 - Album Name"
        val prefixDashRegex = Regex("""^(\d{4})\s*-\s*(.+)$""")
        prefixDashRegex.find(folderName)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val name = match.groupValues[2].trim()
            if (name.isNotEmpty()) return ParsedAlbum(name = name, year = year)
        }

        // Pattern: "Album Name (2020)"
        val suffixParenRegex = Regex("""^(.+)\s*\((\d{4})\)\s*$""")
        suffixParenRegex.find(folderName)?.let { match ->
            val name = match.groupValues[1].trim()
            val year = match.groupValues[2].toIntOrNull()
            if (name.isNotEmpty()) return ParsedAlbum(name = name, year = year)
        }

        // Pattern: "Album Name [2020]"
        val suffixBracketRegex = Regex("""^(.+)\s*\[(\d{4})]\s*$""")
        suffixBracketRegex.find(folderName)?.let { match ->
            val name = match.groupValues[1].trim()
            val year = match.groupValues[2].toIntOrNull()
            if (name.isNotEmpty()) return ParsedAlbum(name = name, year = year)
        }

        // No year found
        return ParsedAlbum(name = folderName.trim())
    }
}
