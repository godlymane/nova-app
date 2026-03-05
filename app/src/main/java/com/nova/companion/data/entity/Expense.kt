package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val currency: String = "INR",
    val category: String,        // food, transport, shopping, bills, entertainment, health, other
    val description: String,
    val merchant: String = "",
    val paymentMethod: String = "", // cash, upi, card, wallet
    val timestamp: Long = System.currentTimeMillis(),
    val isRecurring: Boolean = false,
    val tags: String = ""        // JSON array of tags
)
