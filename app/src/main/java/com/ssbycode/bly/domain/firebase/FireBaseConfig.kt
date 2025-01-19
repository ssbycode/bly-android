package com.ssbycode.bly.domain.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

object FirebaseConfig {

    // Configurações existentes
    private const val DATABASE_URL = "https://bly-app-default-rtdb.firebaseio.com"
    private const val PERSISTENCE_ENABLED = false

    // Função de configuração
    fun initialSetup(context: Context) {
        Log.i("FirebaseConfig", "Configurando Firebase Android")
        FirebaseApp.initializeApp(context)  // Passa o contexto válido
        val database = FirebaseDatabase.getInstance(DATABASE_URL)
        database.setPersistenceEnabled(PERSISTENCE_ENABLED)
    }
}
