package com.brave.veloxcore.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
      tableName = "captures",
      indices = [
          Index(value = ["contentHash"]),
          Index(value = ["timestamp"]),
          Index(value = ["packageName"])
      ]
  )
data class CaptureEntity(
      @PrimaryKey(autoGenerate = true)
      val id: Long = 0,

      val timestamp: Long,              // System.currentTimeMillis()
      val packageName: String,          // "com.whatsapp"
      val eventType: String,            // "APP_SWITCH", "CONTENT_CHANGED", "SCROLLED"
      val textContent: String,          // All extracted text
      val contentHash: String,          // SHA-256 of textContent (for dedup)
  )