package com.ssbycode.bly.domain.firebase

import com.ssbycode.bly.domain.communication.SignalingService
import com.google.firebase.database.*
import kotlin.collections.HashSet
import kotlinx.coroutines.*

private val cleanupScope = CoroutineScope(Dispatchers.Default)


// Enums
enum class SignalType(val value: String) {
    INITIAL("init"),  // Alterado de "initial" para "init"
    OFFER("offer"),
    ANSWER("answer"),
    CANDIDATE("candidate"),
    BYE("bye");

    val finalStatus: SignalStatus
        get() = when (this) {
            INITIAL -> SignalStatus.COMPLETED
            OFFER, ANSWER, CANDIDATE -> SignalStatus.PROCESSING
            BYE -> SignalStatus.COMPLETED
        }
}

enum class SignalStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed")
}

sealed class SignalError : Exception() {
    object InvalidState : SignalError()
    object InvalidSignal : SignalError()
    object Timeout : SignalError()
}


class FirebaseManager(
    private val signalTimeout: Long = 60 // em segundos
) : SignalingService {

    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val activeSignals = HashSet<String>()
    private val observers = mutableMapOf<String, ChildEventListener>()
    private val processingSignals = HashSet<String>()

    init {
        setupInitialConfiguration()
    }

    private fun setupInitialConfiguration() {
        testConnection()
        setupSignalCleanup()
    }

    override fun sendSignal(deviceID: String, type: String, data: String, receiver: String) {
        if (deviceID.isEmpty() || type.isEmpty() || data.isEmpty() || receiver.isEmpty()) {
            println("❌ Invalid signal parameters")
            return
        }

        println("📤 Sending signal with parameters:")
        println("DeviceID: $deviceID")
        println("Type: $type")
        println("Receiver: $receiver")
        println("Data length: ${data.length}")

        val signal = createSignal(deviceID, type, data, receiver)
        println("Created signal: $signal")

        val signalRef = database.child("signals").child(receiver).push()
        println("Signal path: ${signalRef.path}")

        activeSignals.add(signalRef.key ?: "")

        signalRef.setValue(signal).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                println("✅ Signal sent successfully: $type")
            } else {
                println("❌ Error sending signal: ${task.exception?.message}")
                println("❌ Error details: ${task.exception}")
                handleSignalError(signalRef, task.exception ?: Exception("Unknown error"))
            }
        }
    }

    override fun listenSignal(
        deviceID: String,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    ) {
        println("🎧 Starting to listen for signals on device: $deviceID")

        // Limpa listeners anteriores
        stopListening(deviceID)

        val signalsRef = database.child("signals").child(deviceID)
            .orderByChild("status")
            .equalTo(SignalStatus.PENDING.value)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (!processingSignals.contains(snapshot.key)) {
                    handleNewSignal(snapshot, onSignalReceived)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                println("❌ Database error: ${error.message}")
            }
        }

        signalsRef.addChildEventListener(listener)
        observers[deviceID] = listener
    }

    override fun stopListening(deviceID: String) {
        println("🛑 Stopping signal listening for device: $deviceID")

        observers[deviceID]?.let { listener ->
            database.child("signals").child(deviceID).removeEventListener(listener)
            observers.remove(deviceID)
        }

        processingSignals.clear()
    }

    private fun createSignal(
        deviceID: String,
        type: String,
        data: String,
        receiver: String
    ): Map<String, Any> {
        val currentTimestamp = System.currentTimeMillis()
        val timeoutMilliseconds = signalTimeout * 1000
        val expiresAt = currentTimestamp + timeoutMilliseconds

        return mapOf(
            "type" to type,           // ✓ Validação: deve ser um dos valores permitidos
            "data" to data,           // ✓ Validação: deve ser string
            "timestamp" to ServerValue.TIMESTAMP,  // ✓ Validação: deve ser <= now
            "sender" to deviceID,     // ✓ Validação: não pode estar vazio
            "receiver" to receiver,   // ✓ Validação: não pode estar vazio
            "processed" to false,     // ✓ Validação: deve ser booleano
            "expiresAt" to expiresAt, // ✓ Validação: deve ser > now
            "status" to SignalStatus.PENDING.value // ✓ Validação: deve ser um dos valores permitidos
        )
    }

    private fun handleNewSignal(
        snapshot: DataSnapshot,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    ) {
        val signal = snapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {}) ?: return

        val type = signal["type"] as? String ?: return
        val data = signal["data"] as? String ?: return
        val sender = signal["sender"] as? String ?: return

        if (processingSignals.contains(snapshot.key)) {
            println("Signal already being processed: ${snapshot.key}")
            return
        }

        println("📨 New signal received - Type: $type, From: $sender")

        snapshot.key?.let { key ->
            processingSignals.add(key)

            updateSignalStatus(snapshot, SignalStatus.PROCESSING) { success ->
                if (success) {
                    onSignalReceived(type, data, sender) { signalSuccess ->
                        try {
                            val signalType = SignalType.valueOf(type.uppercase())
                            val finalStatus = if (signalSuccess) signalType.finalStatus else SignalStatus.FAILED
                            updateSignalStatus(snapshot, finalStatus)
                        } finally {
                            processingSignals.remove(key)
                        }
                    }
                } else {
                    processingSignals.remove(key)
                }
            }
        }
    }

    private fun updateSignalStatus(
        snapshot: DataSnapshot,
        status: SignalStatus,
        completion: ((Boolean) -> Unit)? = null
    ) {
        val updates = mapOf(
            "status" to status.value,
            "processedAt" to ServerValue.TIMESTAMP
        )

        snapshot.ref.updateChildren(updates)
            .addOnCompleteListener { task ->
                completion?.invoke(task.isSuccessful)

                if (!task.isSuccessful) {
                    println("❌ Error updating signal status: ${task.exception?.message ?: "Unknown error"}")
                }
            }
    }

    private fun handleSignalError(ref: DatabaseReference, error: Exception) {
        val errorUpdate = mapOf(
            "status" to SignalStatus.FAILED.value,
            "error" to error.message
        )

        ref.updateChildren(errorUpdate)
    }

    private fun setupStatusMonitoring(deviceID: String) {
        val statusRef = database.child("signals").child(deviceID)

        val valueEventListener = object : ChildEventListener {
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val signal = snapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})
                val status = signal?.get("status") as? String

                if (status != null) {
                    println("📡 Signal status changed - ID: ${snapshot.key}, Status: $status")
                }
            }

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }

        statusRef.addChildEventListener(valueEventListener)
    }

    private fun setupSignalCleanup() {
        cleanupScope.launch {
            while (isActive) {
                cleanupExpiredSignals()
                delay(300000) // 5 minutos
            }
        }
    }

    private fun cleanupExpiredSignals() {
        val currentTimestamp = System.currentTimeMillis()

        database.child("signals")
            .orderByChild("status")
            .equalTo(SignalStatus.COMPLETED.value)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.children.forEach { child ->
                    val signal = child.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})
                    val expiresAt = (signal?.get("expiresAt") as? Long) ?: return@forEach

                    if (expiresAt < currentTimestamp) {
                        child.ref.removeValue()
                            .addOnSuccessListener {
                                println("✅ Removed expired signal: ${child.key}")
                            }
                            .addOnFailureListener { e ->
                                println("❌ Failed to remove expired signal: ${e.message}")
                            }
                    }
                }
            }
    }

    private fun testConnection() {
//        val testRef = database.child("test/")
//        testRef.addValueEventListener(object : ValueEventListener {
//            override fun onDataChange(snapshot: DataSnapshot) {
//                val connected = snapshot.getValue(Boolean::class.java) ?: false
//                println(if (connected) "✅ Connected to Firebase" else "❌ Disconnected from Firebase")
//            }
//
//            override fun onCancelled(error: DatabaseError) {
//                println("❌ Connection test failed: ${error.message}")
//            }
//        })
    }
}