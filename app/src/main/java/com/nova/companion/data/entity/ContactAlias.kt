package com.nova.companion.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_aliases")
data class ContactAlias(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val alias: String,           // "a", "bro", "robo"
    val contactName: String,     // "Mom", "Shreyesh Sharma"
    val phoneNumber: String? = null  // optional direct number override
)
