package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.model.processor.A2uiJsonReader
import androidx.a2ui.model.processor.A2uiJsonToken
import java.io.StringReader

/** android.util.JsonReader → A2uiJsonReader 适配器（androidx.a2ui 未附带平台实现）。 */
class AndroidA2uiJsonReader(input: String) : A2uiJsonReader {

    private val reader = android.util.JsonReader(StringReader(input))

    override fun peek(): A2uiJsonToken =
        when (reader.peek()) {
            android.util.JsonToken.BEGIN_ARRAY -> A2uiJsonToken.BEGIN_ARRAY
            android.util.JsonToken.END_ARRAY -> A2uiJsonToken.END_ARRAY
            android.util.JsonToken.BEGIN_OBJECT -> A2uiJsonToken.BEGIN_OBJECT
            android.util.JsonToken.END_OBJECT -> A2uiJsonToken.END_OBJECT
            android.util.JsonToken.NAME -> A2uiJsonToken.NAME
            android.util.JsonToken.STRING -> A2uiJsonToken.STRING
            android.util.JsonToken.NUMBER -> A2uiJsonToken.NUMBER
            android.util.JsonToken.BOOLEAN -> A2uiJsonToken.BOOLEAN
            android.util.JsonToken.NULL -> A2uiJsonToken.NULL
            android.util.JsonToken.END_DOCUMENT -> A2uiJsonToken.END_DOCUMENT
        }

    override fun beginObject() = reader.beginObject()

    override fun endObject() = reader.endObject()

    override fun beginArray() = reader.beginArray()

    override fun endArray() = reader.endArray()

    override fun hasNext(): Boolean = reader.hasNext()

    override fun nextName(): String = reader.nextName()

    override fun nextString(): String = reader.nextString()

    override fun nextBoolean(): Boolean = reader.nextBoolean()

    override fun nextDouble(): Double = reader.nextDouble()

    override fun nextInt(): Int = reader.nextInt()

    override fun nextLong(): Long = reader.nextLong()

    override fun nextNull() = reader.nextNull()

    override fun skipValue() = reader.skipValue()

    override fun close() = reader.close()
}
