package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.NovaTask
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

object TaskToolExecutor {

    private const val TAG = "TaskTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "tasks",
            description = "Manage tasks and to-do items. Actions: 'add' to create task, 'list' to see pending tasks, 'done' to complete, 'search' to find tasks.",
            parameters = mapOf(
                "action" to ToolParam("string", "Action: add, list, done, search, overdue", true),
                "title" to ToolParam("string", "Task title (for 'add')", false),
                "description" to ToolParam("string", "Task details", false),
                "priority" to ToolParam("string", "Priority: low, medium, high, urgent", false),
                "category" to ToolParam("string", "Category: work, personal, health, finance, social", false),
                "due" to ToolParam("string", "Due date: today, tomorrow, friday, next week", false),
                "id" to ToolParam("number", "Task ID (for 'done')", false),
                "query" to ToolParam("string", "Search query", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "list"
        val db = NovaDatabase.getInstance(context)

        return try {
            when (action) {
                "add" -> addTask(db, params)
                "list" -> listTasks(db, params)
                "done" -> completeTask(db, params)
                "search" -> searchTasks(db, params)
                "overdue" -> getOverdue(db)
                else -> ToolResult(false, "Unknown action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task tool error", e)
            ToolResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun addTask(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val title = params["title"] as? String
            ?: return ToolResult(false, "Task title required.")

        val priority = when ((params["priority"] as? String)?.lowercase()) {
            "low" -> 1; "medium" -> 2; "high" -> 3; "urgent" -> 4
            else -> 2
        }

        val category = (params["category"] as? String)?.lowercase() ?: ""
        val description = params["description"] as? String ?: ""
        val dueDate = parseDueDate((params["due"] as? String) ?: "")

        val task = NovaTask(
            title = title,
            description = description,
            priority = priority,
            category = category,
            dueDate = dueDate
        )
        val id = db.taskDao().insert(task)
        val pending = db.taskDao().getPendingCount()

        val priorityLabel = when (priority) {
            1 -> "low"; 2 -> "medium"; 3 -> "high"; 4 -> "urgent"; else -> "medium"
        }

        return ToolResult(true, buildString {
            append("Task #$id created: $title")
            append(" [$priorityLabel]")
            if (category.isNotBlank()) append(" ($category)")
            if (dueDate != null) {
                val df = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())
                append("\nDue: ${df.format(Date(dueDate))}")
            }
            append("\nYou have $pending pending tasks.")
        })
    }

    private suspend fun listTasks(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val category = params["category"] as? String

        val tasks = if (category != null) {
            db.taskDao().getByCategory(category.lowercase())
        } else {
            db.taskDao().getActiveTasks()
        }

        if (tasks.isEmpty()) {
            return ToolResult(true, "No pending tasks. You're all clear!")
        }

        val df = SimpleDateFormat("MMM dd", Locale.getDefault())
        return ToolResult(true, buildString {
            append("Pending tasks (${tasks.size}):\n")
            tasks.forEachIndexed { _, t ->
                val prioIcon = when (t.priority) {
                    4 -> "🔴"; 3 -> "🟠"; 2 -> "🟡"; else -> "⚪"
                }
                append("  $prioIcon #${t.id} ${t.title}")
                if (t.dueDate != null) append(" (due ${df.format(Date(t.dueDate))})")
                if (t.category.isNotBlank()) append(" [${t.category}]")
                append("\n")
            }
        })
    }

    private suspend fun completeTask(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val id = when (val i = params["id"]) {
            is Number -> i.toLong()
            is String -> i.toLongOrNull()
            else -> null
        } ?: return ToolResult(false, "Task ID required to mark as done.")

        db.taskDao().markDone(id)
        val pending = db.taskDao().getPendingCount()
        return ToolResult(true, "Task #$id marked as done! $pending tasks remaining.")
    }

    private suspend fun searchTasks(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val query = params["query"] as? String
            ?: return ToolResult(false, "Search query required.")

        val tasks = db.taskDao().search(query)
        if (tasks.isEmpty()) return ToolResult(true, "No tasks found matching '$query'.")

        return ToolResult(true, buildString {
            append("Found ${tasks.size} tasks:\n")
            tasks.forEach { t ->
                append("  #${t.id} ${t.title} [${t.status}]")
                if (t.category.isNotBlank()) append(" ($t.category)")
                append("\n")
            }
        })
    }

    private suspend fun getOverdue(db: NovaDatabase): ToolResult {
        val overdue = db.taskDao().getOverdue()
        if (overdue.isEmpty()) return ToolResult(true, "No overdue tasks!")

        val df = SimpleDateFormat("MMM dd", Locale.getDefault())
        return ToolResult(true, buildString {
            append("Overdue tasks (${overdue.size}):\n")
            overdue.forEach { t ->
                append("  #${t.id} ${t.title}")
                if (t.dueDate != null) append(" (was due ${df.format(Date(t.dueDate))})")
                append("\n")
            }
        })
    }

    private fun parseDueDate(input: String): Long? {
        if (input.isBlank()) return null
        val lower = input.lowercase().trim()
        val cal = Calendar.getInstance()

        when {
            "today" in lower -> {
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
            }
            "tomorrow" in lower -> {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 17)
                cal.set(Calendar.MINUTE, 0)
            }
            "next week" in lower -> {
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
            }
            else -> {
                // Try weekday names
                val dayMap = mapOf(
                    "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
                    "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
                    "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
                    "sunday" to Calendar.SUNDAY
                )
                dayMap.entries.firstOrNull { lower.contains(it.key) }?.let { (_, day) ->
                    while (cal.get(Calendar.DAY_OF_WEEK) != day) {
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    cal.set(Calendar.HOUR_OF_DAY, 17)
                    cal.set(Calendar.MINUTE, 0)
                } ?: return null
            }
        }
        return cal.timeInMillis
    }
}
