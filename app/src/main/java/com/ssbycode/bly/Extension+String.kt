package com.ssbycode.bly

val String.formattedDeviceID: String
    get() = this.split("-").firstOrNull() ?: this

