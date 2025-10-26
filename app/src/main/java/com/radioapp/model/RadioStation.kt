package com.radioapp.model

data class RadioStation(
    val id: Int,
    val name: String,
    val url: String,
    val genre: String = "Unknown",
    val logoResId: Int
)