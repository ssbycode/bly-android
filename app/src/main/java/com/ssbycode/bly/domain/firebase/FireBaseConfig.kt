package com.ssbycode.bly.domain.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

object FirebaseConfig {

    private const val DATABASE_URL = "https://bly-app-default-rtdb.firebaseio.com"
    private const val PERSISTENCE_ENABLED = false

    fun initialSetup(context: Context) {
        FirebaseApp.initializeApp(context)

        val database = FirebaseDatabase.getInstance(DATABASE_URL)
        database.setPersistenceEnabled(PERSISTENCE_ENABLED)
        Log.d("Firebase", "Configurando Firebase...")
    }
}
