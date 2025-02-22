package com.ssbycode.bly.domain.firebase

import com.google.firebase.database.*
import com.ssbycode.bly.domain.communication.SignalingService
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.HashSet

// MARK: - Key Abbreviations
/*
 For data optimization, we use abbreviated keys:
 t  = type
 d  = data
 ts = timestamp
 s  = sender
 r  = receiver
 ea = expiresAt
 st = status
 e  = error
 pa = processedAt
*/

// MARK: - Enums and Protocols
enum class SignalType(val value: String) {
    INITIAL("i"),    // initial
    OFFER("o"),      // offer
    ANSWER("a"),     // answer
    CANDIDATE("c"),  // candidate
    BYE("b");        // bye

    val finalStatus: SignalStatus
        get() = when (this) {
            INITIAL, BYE -> SignalStatus.COMPLETED
            OFFER, ANSWER, CANDIDATE -> SignalStatus.PROCESSING
        }

    companion object {
        fun fromString(value: String): SignalType? {
            return values().firstOrNull { it.value == value.lowercase().take(1) }
        }
    }
}

enum class SignalStatus(val value: String) {
    PENDING("p"),        // pending
    PROCESSING("pr"),    // processing
    COMPLETED("c"),      // completed
    FAILED("f");         // failed
}

sealed class SignalError : Exception() {
    object InvalidState : SignalError()
    object InvalidSignal : SignalError()
    object Timeout : SignalError()
}

class FirebaseManager(
    private val localDeviceID: String = UUID.randomUUID().toString()
) : SignalingService {

    // MARK: - Properties
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val signalCache = SignalCache()
    private val activeSignals = HashSet<String>()
    private val observers = mutableMapOf<String, ValueEventListener>()
    private val processingSignals = HashSet<String>()
    private val processingTimestamps = mutableMapOf<String, Long>()
    private val cleanupInterval: Long = 15000 // 15 seconds

    private val signalTimeouts = mapOf(
        "b" to 10000L,    // 10 seconds for bye
        "c" to 30000L,    // 30 seconds for candidate
        "default" to 60000L // 60 seconds default
    )

    private val cleanupJob = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            cleanupExpiredSignals()
            delay(cleanupInterval)
        }
    }

    init {
        setupInitialConfiguration()
    }

    // MARK: - Initialization
    private fun setupInitialConfiguration() {
        testConnection()
        countAllSignals { count ->
            println("Total signals found: $count")
        }
    }

    // MARK: - Public Methods
    override fun sendSignal(deviceID: String, type: String, data: String, receiver: String) {
        if (deviceID.isEmpty() || type.isEmpty() || receiver.isEmpty()) {
            println("❌ Invalid signal parameters")
            return
        }

        println("📤 Sending $type signal to device: $receiver")

        val signal = createSignal(deviceID, type, data, receiver)
        val signalRef = database.child("signals").child(receiver).push()

        signalCache.store(signalId = signalRef.key ?: "", signal = signal)
        activeSignals.add(signalRef.key ?: "")

        signalRef.setValue(signal)
            .addOnSuccessListener {
                println("✅ Signal sent successfully: $type")
            }
            .addOnFailureListener { error ->
                println("❌ Error sending signal: ${error.message}")
                handleSignalError(signalRef, error)
            }
    }

    override fun listenSignal(
        deviceID: String,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    ) {
        println("🎧 Starting to listen for signals on device: $deviceID")

        stopListening(deviceID)

        val signalsRef = database.child("signals").child(deviceID)
            .orderByChild("st")
            .equalTo(SignalStatus.PENDING.value)

        val listener = object : ChildEventListener {
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

        signalsRef.addChildEventListener(listener)
        setupStatusMonitoring(deviceID)
    }

    override fun stopListening(deviceID: String) {
        println("🛑 Stopping signal listening for device: $deviceID")

        observers[deviceID]?.let { listener ->
            database.child("signals").child(deviceID).removeEventListener(listener)
            observers.remove(deviceID)
        }

        processingSignals.clear()
        processingTimestamps.clear()
    }

    // MARK: - Private Methods
    private fun createSignal(
        deviceID: String,
        type: String,
        data: String,
        receiver: String
    ): Map<String, Any> {
        val currentTimestamp = System.currentTimeMillis()
        val timeout = signalTimeouts[type] ?: signalTimeouts["default"]!!
        val expiresAt = currentTimestamp + timeout

        return mapOf(
            "t" to type,        // type
            "d" to data,        // data
            "ts" to ServerValue.TIMESTAMP,  // timestamp
            "s" to deviceID,    // sender
            "r" to receiver,    // receiver
            "ea" to expiresAt,  // expiresAt
            "st" to SignalStatus.PENDING.value // status
        )
    }

    private fun handleNewSignal(
        snapshot: DataSnapshot,
        onSignalReceived: (type: String, data: String, sender: String, completion: (Boolean) -> Unit) -> Unit
    ) {
        // Check cache first
        signalCache.retrieve(snapshot.key ?: "")?.let { cachedSignal ->
            val type = cachedSignal["t"] as? String
            val data = cachedSignal["d"] as? String
            val sender = cachedSignal["s"] as? String

            if (type != null && data != null && sender != null) {
                println("📤 Using cached signal: ${snapshot.key}")
                onSignalReceived(type, data, sender) { success ->
                    if (success) {
                        signalCache.remove(snapshot.key ?: "")
                    }
                }
                return
            }
        }

        // If not in cache, process normally
        val signal = snapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {}) ?: return
        val type = signal["t"] as? String ?: return
        val data = signal["d"] as? String ?: return
        val sender = signal["s"] as? String ?: return

        // Store in cache
        signalCache.store(signalId = snapshot.key ?: "", signal = signal)

        println("📨 Processing new signal - Type: $type, From: $sender")
        onSignalReceived(type, data, sender) { success ->
            if (success) {
                signalCache.remove(snapshot.key ?: "")
            }
        }
    }

    private fun updateSignalStatus(
        snapshot: DataSnapshot,
        status: SignalStatus,
        completion: ((Boolean) -> Unit)? = null
    ) {
        val updates = mapOf("st" to status.value)

        snapshot.ref.updateChildren(updates)
            .addOnCompleteListener { task ->
                completion?.invoke(task.isSuccessful)

                // If signal completed, remove immediately
                if (task.isSuccessful && status == SignalStatus.COMPLETED) {
                    snapshot.ref.removeValue()
                }
            }
    }

    private fun handleSignalError(ref: DatabaseReference, error: Exception) {
        val errorUpdate = mapOf(
            "st" to SignalStatus.FAILED.value,  // status
            "e" to error.message                // error
        )

        ref.updateChildren(errorUpdate)
    }

    private fun setupStatusMonitoring(deviceID: String) {
        val statusRef = database.child("signals").child(deviceID)

        statusRef.addChildEventListener(object : ChildEventListener {
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val signal = snapshot.getValue(object : GenericTypeIndicator<Map<String, Any>>() {})
                val status = signal?.get("st") as? String

                if (status != null) {
                    println("📡 Signal status changed - ID: ${snapshot.key}, Status: $status")
                }
            }

            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // MARK: - Cleanup
    private fun cleanupExpiredSignals() {
        val currentTimestamp = System.currentTimeMillis()

        val signalsRef = database.child("signals").child(localDeviceID)

        // Query for expired signals
        signalsRef.orderByChild("ea")
            .endAt(currentTimestamp.toDouble())
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.children.forEach { it.ref.removeValue() }
            }

        // Query for completed signals
        signalsRef.orderByChild("st")
            .equalTo(SignalStatus.COMPLETED.value)
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.children.forEach { it.ref.removeValue() }
            }
    }

    private fun cleanupProcessingSignals() {
        val currentTimestamp = System.currentTimeMillis()
        val staleTimestamp = currentTimestamp - 30000 // 30 seconds

        // Remove old signals from processingSignals
        processingTimestamps.entries
            .filter { it.value < staleTimestamp }
            .forEach { (signalId, _) ->
                processingSignals.remove(signalId)
                processingTimestamps.remove(signalId)
            }
    }

    private fun testConnection() {
        val connectedRef = database.child(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                println(if (connected) "✅ Connected to Firebase" else "❌ Disconnected from Firebase")
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // MARK: - Debug Methods
    fun countAllSignals(completion: (Int) -> Unit) {
        database.child("signals").get()
            .addOnSuccessListener { snapshot ->
                var totalCount = 0
                snapshot.children.forEach { deviceSnapshot ->
                    totalCount += deviceSnapshot.childrenCount.toInt()
                }
                completion(totalCount)
            }
            .addOnFailureListener {
                completion(0)
            }
    }
}