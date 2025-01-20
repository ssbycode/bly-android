package com.ssbycode.bly.data.realTimeCommunication

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class TimestampAdapter : TypeAdapter<Long>() {
    override fun write(out: JsonWriter, value: Long) {
        out.value(value)
    }

    override fun read(input: JsonReader): Long {
        return when (input.peek()) {
            JsonToken.NUMBER -> {
                val number = input.nextDouble()
                (number * 1000).toLong() // Convertendo para milissegundos
            }
            else -> {
                input.skipValue()
                0L
            }
        }
    }
}