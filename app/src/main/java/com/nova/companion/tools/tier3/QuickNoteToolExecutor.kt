package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object QuickNoteToolExecutor {

    private const val TAG = "QuickNoteTool"
    private const val NOTES_DIR = "nova_notes"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "quickNote",
            description = "Save a quick note locally on the device. Say \"list\" to show saved notes.",
            parameters = mapOf(
                "content" to ToolParam(type = "string", description = "The note content, or \"list\" to show all saved notes", required = true),
                "title" to ToolParam(type = "string", description = "An optional title for the note", required = false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        return try {
            val content = (params["content"] as? String)?.trim()
                ?: return ToolResult(false, "Note content is required")
            val title = (params["title"] as? String)?.trim()

            val notesDir = File(context.filesDir, NOTES_DIR)
            if (!notesDir.exists()) {
                notesDir.mkdirs()
            }

            val contentLower = content.lowercase()
            if (contentLower == "list" || contentLower == "show notes" || contentLower == "show all notes") {
                return listNotes(notesDir)
            }

            saveNote(notesDir, content, title)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle note", e)
            ToolResult(false, "Failed to save note: ${e.message}")
        }
    }

    private fun saveNote(notesDir: File, content: String, title: String?): ToolResult {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(timestamp))
        val noteTitle = title ?: "Note at $formattedDate"

        val fileName = "note_$timestamp.txt"
        val noteFile = File(notesDir, fileName)

        val noteContent = buildString {
            appendLine(noteTitle)
            appendLine("---")
            appendLine(content)
            appendLine()
            appendLine("Saved by Nova at $formattedDate")
        }

        noteFile.writeText(noteContent)

        val displayTitle = title ?: content.take(30).let { if (content.length > 30) "$it..." else it }
        val message = "Note saved: $displayTitle"
        Log.i(TAG, "$message (file: $fileName)")
        return ToolResult(true, message)
    }

    private fun listNotes(notesDir: File): ToolResult {
        val noteFiles = notesDir.listFiles { file -> file.name.startsWith("note_") && file.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }

        if (noteFiles.isNullOrEmpty()) {
            return ToolResult(true, "You don't have any saved notes yet")
        }

        val notesList = buildString {
            appendLine("Your saved notes (${noteFiles.size} total):")
            noteFiles.take(10).forEachIndexed { index, file ->
                val lines = file.readLines()
                val noteTitle = lines.firstOrNull() ?: "Untitled"
                val firstContent = lines.drop(2).firstOrNull { it.isNotBlank() } ?: ""
                val preview = firstContent.take(50).let { if (firstContent.length > 50) "$it..." else it }
                appendLine("${index + 1}. $noteTitle — $preview")
            }
            if (noteFiles.size > 10) {
                appendLine("...and ${noteFiles.size - 10} more")
            }
        }

        Log.i(TAG, "Listed ${noteFiles.size} notes")
        return ToolResult(true, notesList.trim())
    }
}
