package com.ssbycode.bly.domain.firebase

import com.ssbycode.bly.domain.communication.SignalingService
import com.google.firebase.database.*
import java.util.*
import kotlin.collections.HashSet

// Enums
enum class SignalType(val value: String) {
    INITIAL("initial"),
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

        println("📤 Sending $type signal to device: $receiver")

        val signal = createSignal(deviceID, type, data, receiver)
        val signalRef = database.child("signals").child(receiver).push()

        activeSignals.add(signalRef.key ?: "")

        signalRef.setValue(signal).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                println("✅ Signal sent successfully: $type")
            } else {
                println("❌ Error sending signal: ${task.exception?.message}")
                handleSignalError(signalRef, task.exception ?: Exception("Unknown error"))
            }
        }
    }

    override fun listenSignal(
        deviceID: String,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    ) {
        println("🎧 Starting to listen for signals on device: $deviceID")

        // Remove existing observers first
        stopListening(deviceID)

        val signalsRef = database.child("signals").child(deviceID)
            .orderByChild("status")
            .equalTo(SignalStatus.PENDING.value)

        val valueEventListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleNewSignal(snapshot, onSignalReceived)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                println("❌ Database error: ${error.message}")
            }
        }

        signalsRef.addChildEventListener(valueEventListener)
        observers[deviceID] = valueEventListener

        // Monitor status changes
        setupStatusMonitoring(deviceID)
    }

    override fun stopListening(deviceID: String) {
        println("🛑 Stopping signal listening for device: $deviceID")

        observers[deviceID]?.let { listener ->
            database.child("signals").child(deviceID).removeEventListener(listener)
            observers.remove(deviceID)
        }

        processingSignals.clear()
    }

    private fun createSignal(deviceID: String, type: String, data: String, receiver: String): Map<String, Any> {
        val currentTimestamp = System.currentTimeMillis()
        val timeoutMilliseconds = signalTimeout * 1000
        val expiresAt = currentTimestamp + timeoutMilliseconds

        return mapOf(
            "type" to type,
            "data" to data,
            "timestamp" to ServerValue.TIMESTAMP,
            "sender" to deviceID,
            "receiver" to receiver,
            "processed" to false,
            "expiresAt" to expiresAt,
            "status" to SignalStatus.PENDING.value
        )
    }

    private fun handleNewSignal(
        snapshot: DataSnapshot,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    ) {
        val signal = snapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})

        if (signal == null ||
            processingSignals.contains(snapshot.key) ||
            signal["type"] !is String ||
            signal["data"] !is String ||
            signal["sender"] !is String) {
            println("❌ Invalid signal format or signal already being processed")
            return
        }

        val type = signal["type"] as String
        val data = signal["data"] as String
        val sender = signal["sender"] as String

        println("📨 New signal received - Type: $type, From: $sender")

        processingSignals.add(snapshot.key!!)

        updateSignalStatus(snapshot, SignalStatus.PROCESSING) { success ->
            if (success) {
                onSignalReceived(type, data, sender) { success ->
                    try {
                        val signalType = SignalType.valueOf(type.uppercase())
                        val finalStatus = if (success) signalType.finalStatus else SignalStatus.FAILED
                        updateSignalStatus(snapshot, finalStatus)
                    } finally {
                        processingSignals.remove(snapshot.key)
                    }
                }
            } else {
                processingSignals.remove(snapshot.key)
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
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                cleanupExpiredSignals()
            }
        }, 0, 300000) // 300 segundos = 5 minutos
    }

    private fun cleanupExpiredSignals() {
        val currentTimestamp = System.currentTimeMillis()

        val completedSignalsQuery = database.child("signals")
            .orderByChild("status")
            .equalTo(SignalStatus.COMPLETED.value)

        completedSignalsQuery.get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { child ->
                val signal = child.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})
                val expiresAt = signal?.get("expiresAt") as? Long

                if (expiresAt != null && expiresAt < currentTimestamp) {
                    child.ref.removeValue()
                }
            }
        }
    }

    private fun testConnection() {
        val testRef = database.child("info/connected")
        testRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                println(if (connected) "✅ Connected to Firebase" else "❌ Disconnected from Firebase")
            }

            override fun onCancelled(error: DatabaseError) {
                println("❌ Connection test failed: ${error.message}")
            }
        })
    }
}