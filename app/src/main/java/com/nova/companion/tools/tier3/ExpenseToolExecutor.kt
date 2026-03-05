package com.nova.companion.tools.tier3

import android.content.Context
import android.util.Log
import com.nova.companion.data.NovaDatabase
import com.nova.companion.data.entity.Expense
import com.nova.companion.tools.NovaTool
import com.nova.companion.tools.ToolParam
import com.nova.companion.tools.ToolRegistry
import com.nova.companion.tools.ToolResult
import java.text.SimpleDateFormat
import java.util.*

object ExpenseToolExecutor {

    private const val TAG = "ExpenseTool"

    fun register(registry: ToolRegistry) {
        val tool = NovaTool(
            name = "trackExpense",
            description = "Track expenses, check spending, and get budget insights. Actions: 'add' to log expense, 'summary' for spending overview, 'search' to find specific expenses, 'category' for category breakdown.",
            parameters = mapOf(
                "action" to ToolParam("string", "Action: add, summary, search, category", true),
                "amount" to ToolParam("number", "Expense amount (for 'add')", false),
                "category" to ToolParam("string", "Category: food, transport, shopping, bills, entertainment, health, other", false),
                "description" to ToolParam("string", "What the expense was for", false),
                "merchant" to ToolParam("string", "Store/vendor name", false),
                "payment" to ToolParam("string", "Payment method: cash, upi, card, wallet", false),
                "period" to ToolParam("string", "Time period: today, week, month, year (for summary/category)", false),
                "query" to ToolParam("string", "Search query (for 'search')", false)
            ),
            executor = { ctx, params -> execute(ctx, params) }
        )
        registry.registerTool(tool)
    }

    private suspend fun execute(context: Context, params: Map<String, Any>): ToolResult {
        val action = (params["action"] as? String)?.lowercase() ?: "summary"
        val db = NovaDatabase.getInstance(context)

        return try {
            when (action) {
                "add" -> addExpense(db, params)
                "summary" -> getSummary(db, params)
                "search" -> searchExpenses(db, params)
                "category" -> getCategoryBreakdown(db, params)
                else -> ToolResult(false, "Unknown action: $action. Use add, summary, search, or category.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Expense tool error", e)
            ToolResult(false, "Error: ${e.message}")
        }
    }

    private suspend fun addExpense(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val amount = when (val a = params["amount"]) {
            is Number -> a.toDouble()
            is String -> a.toDoubleOrNull()
            else -> null
        } ?: return ToolResult(false, "Amount is required for adding an expense.")

        val category = (params["category"] as? String)?.lowercase() ?: "other"
        val description = params["description"] as? String ?: ""
        val merchant = params["merchant"] as? String ?: ""
        val payment = params["payment"] as? String ?: ""

        val expense = Expense(
            amount = amount,
            category = category,
            description = description,
            merchant = merchant,
            paymentMethod = payment
        )

        db.expenseDao().insert(expense)

        // Get today's total
        val (startOfDay, endOfDay) = getDayRange(0)
        val todayTotal = db.expenseDao().getTotalByRange(startOfDay, endOfDay) ?: 0.0

        // Get month total
        val (startOfMonth, endOfMonth) = getMonthRange()
        val monthTotal = db.expenseDao().getTotalByRange(startOfMonth, endOfMonth) ?: 0.0

        return ToolResult(true, buildString {
            append("Logged: ₹${formatAmount(amount)}")
            if (description.isNotBlank()) append(" — $description")
            if (merchant.isNotBlank()) append(" at $merchant")
            append(" [$category]")
            if (payment.isNotBlank()) append(" via $payment")
            append("\nToday's total: ₹${formatAmount(todayTotal)}")
            append(" | This month: ₹${formatAmount(monthTotal)}")
        })
    }

    private suspend fun getSummary(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val period = (params["period"] as? String)?.lowercase() ?: "month"
        val (start, end) = when (period) {
            "today" -> getDayRange(0)
            "yesterday" -> getDayRange(-1)
            "week" -> getWeekRange()
            "month" -> getMonthRange()
            "year" -> getYearRange()
            else -> getMonthRange()
        }

        val total = db.expenseDao().getTotalByRange(start, end) ?: 0.0
        val categories = db.expenseDao().getCategoryTotals(start, end)
        val expenses = db.expenseDao().getByDateRange(start, end)
        val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

        return ToolResult(true, buildString {
            append("Spending summary ($period):\n")
            append("Total: ₹${formatAmount(total)} across ${expenses.size} transactions\n\n")

            if (categories.isNotEmpty()) {
                append("By category:\n")
                categories.forEach { cat ->
                    val pct = if (total > 0) (cat.total / total * 100).toInt() else 0
                    append("  ${cat.category}: ₹${formatAmount(cat.total)} ($pct%)\n")
                }
            }

            // Daily average
            val days = when (period) {
                "today" -> 1
                "week" -> 7
                "month" -> 30
                "year" -> 365
                else -> 30
            }
            if (days > 1 && total > 0) {
                append("\nDaily avg: ₹${formatAmount(total / days)}")
            }

            // Recent 5 transactions
            if (expenses.isNotEmpty()) {
                append("\n\nRecent:\n")
                expenses.take(5).forEach { e ->
                    append("  ${dateFormat.format(Date(e.timestamp))} — ₹${formatAmount(e.amount)} ${e.description}")
                    if (e.merchant.isNotBlank()) append(" @ ${e.merchant}")
                    append("\n")
                }
            }
        })
    }

    private suspend fun searchExpenses(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val query = params["query"] as? String
            ?: return ToolResult(false, "Search query required.")
        val results = db.expenseDao().search(query)
        val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

        return if (results.isEmpty()) {
            ToolResult(true, "No expenses found matching '$query'.")
        } else {
            ToolResult(true, buildString {
                append("Found ${results.size} expenses matching '$query':\n")
                results.forEach { e ->
                    append("  ${dateFormat.format(Date(e.timestamp))} — ₹${formatAmount(e.amount)} ${e.description}")
                    if (e.merchant.isNotBlank()) append(" @ ${e.merchant}")
                    append(" [${e.category}]\n")
                }
                val total = results.sumOf { it.amount }
                append("Total: ₹${formatAmount(total)}")
            })
        }
    }

    private suspend fun getCategoryBreakdown(db: NovaDatabase, params: Map<String, Any>): ToolResult {
        val category = (params["category"] as? String)?.lowercase()
        val period = (params["period"] as? String)?.lowercase() ?: "month"
        val (start, end) = when (period) {
            "today" -> getDayRange(0)
            "week" -> getWeekRange()
            "month" -> getMonthRange()
            "year" -> getYearRange()
            else -> getMonthRange()
        }

        return if (category != null) {
            val total = db.expenseDao().getCategoryTotal(category, start, end) ?: 0.0
            val expenses = db.expenseDao().getByCategory(category)
                .filter { it.timestamp in start..end }
            val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())

            ToolResult(true, buildString {
                append("$category spending ($period): ₹${formatAmount(total)}\n")
                append("${expenses.size} transactions:\n")
                expenses.take(10).forEach { e ->
                    append("  ${dateFormat.format(Date(e.timestamp))} — ₹${formatAmount(e.amount)} ${e.description}\n")
                }
            })
        } else {
            val categories = db.expenseDao().getCategoryTotals(start, end)
            val total = categories.sumOf { it.total }
            ToolResult(true, buildString {
                append("Category breakdown ($period):\n")
                categories.forEach { cat ->
                    val pct = if (total > 0) (cat.total / total * 100).toInt() else 0
                    val bar = "█".repeat((pct / 5).coerceIn(0, 20))
                    append("  ${cat.category.padEnd(14)} ₹${formatAmount(cat.total).padStart(8)}  $bar $pct%\n")
                }
                append("\nTotal: ₹${formatAmount(total)}")
            })
        }
    }

    // --- Helpers ---

    private fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format("%.2f", amount)
        }
    }

    private fun getDayRange(offset: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, offset)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return Pair(start, cal.timeInMillis)
    }

    private fun getWeekRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        return Pair(start, cal.timeInMillis)
    }

    private fun getMonthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return Pair(start, cal.timeInMillis)
    }

    private fun getYearRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.YEAR, 1)
        return Pair(start, cal.timeInMillis)
    }
}
