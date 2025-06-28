package nl.tudelft.trustchain.eurotoken.common

import org.json.JSONObject

class ConnectionData {
    var publicKey: String = ""
    var amount: Long = -1L
    var name: String = ""
    var type: String = ""
    var timestamp: Long = 0L

    constructor(json: String) {
        try {
            val jsonObject = JSONObject(json)
            publicKey = jsonObject.optString("public_key", "")
            amount = jsonObject.optLong("amount", -1L)
            name = jsonObject.optString("name", "")
            type = jsonObject.optString("type", "")
            timestamp = jsonObject.optLong("timestamp", System.currentTimeMillis())
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid connection data format", e)
        }
    }

    constructor(
        publicKey: String,
        amount: Long,
        name: String,
        type: String
    ) {
        this.publicKey = publicKey
        this.amount = amount
        this.name = name
        this.type = type
        this.timestamp = System.currentTimeMillis()
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("public_key", publicKey)
            put("amount", amount)
            put("name", name)
            put("type", type)
            put("timestamp", timestamp)
        }.toString()
    }

    fun isValid(): Boolean {
        return publicKey.isNotEmpty() &&
            amount >= 0 &&
            type.isNotEmpty()
    }

    fun isExpired(maxAgeMillis: Long = 5 * 60 * 1000): Boolean {
        return System.currentTimeMillis() - timestamp > maxAgeMillis
    }

    companion object {
        const val TYPE_TRANSFER_REQUEST = "transfer_request"
        const val TYPE_PAYMENT_REQUEST = "payment_request"
        const val TYPE_REQUEST = "request" // previous->deprecate
    }
}
