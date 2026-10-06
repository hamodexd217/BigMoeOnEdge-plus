package com.bigmoe.onedge.data.local.converter

import androidx.room.TypeConverter
import com.bigmoe.onedge.data.local.entity.MessageRole

class Converters {

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole =
        MessageRole.values().firstOrNull { it.name == value } ?: MessageRole.USER
}
