package nl.tudelft.trustchain.eurotoken.community

import android.content.Context
import android.util.Log
import kotlin.random.Random
import nl.tudelft.ipv8.Community
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.trustchain.common.eurotoken.Transaction
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.eurotoken.EurotokenPreferences.DEMO_MODE_ENABLED
import nl.tudelft.trustchain.common.eurotoken.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME
import nl.tudelft.trustchain.eurotoken.db.TrustStore
import nl.tudelft.trustchain.eurotoken.ui.settings.DefaultGateway
import nl.tudelft.ipv8.util.hexToBytes

class EuroTokenCommunity(
    store: GatewayStore,
    trustStore: TrustStore,
    context: Context,
) : Community() {
    override val serviceId = "f0eb36102436bd55c7a3cdca93dcaefb08df0750"

    private lateinit var transactionRepository: TransactionRepository

    /**
     * The [TrustStore] used to fetch and update trust scores from peers.
     */
    private var myTrustStore: TrustStore

    /**
     * The context used to access the shared preferences.
     */
    private var myContext: Context

    companion object {
    //     * Every community initializes a different version of the EVA protocol (if enabled).
    //  * To distinguish the incoming packets/requests an ID must be used to hold/let through the
    //  * EVA related packets.
        object EVAId {
            const val EVA_LAST_ADDRESSES = "eva_last_addresses"
        }

        class Factory(
            private val store: GatewayStore,
            private val trustStore: TrustStore,
            private val context: Context,
        ) : Overlay.Factory<EuroTokenCommunity>(EuroTokenCommunity::class.java) {
            override fun create(): EuroTokenCommunity {
                return EuroTokenCommunity(store, trustStore, context)
            }
        }
    }

    init {
        messageHandlers[MessageId.ROLLBACK_REQUEST] = ::onRollbackRequestPacket
        messageHandlers[MessageId.ATTACHMENT] = ::onLastAddressPacket
        if (store.getPreferred().isEmpty()) {
            DefaultGateway.addGateway(store)
        }

        myTrustStore = trustStore
        myContext = context
    }

    @JvmName("setTransactionRepository1")
    fun setTransactionRepository(transactionRepositoryLocal: TransactionRepository) {
        transactionRepository = transactionRepositoryLocal
    }

    private fun onRollbackRequestPacket(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(RollbackRequestPayload.Deserializer)
        onRollbackRequest(peer, payload)
    }

    /**
     * Called upon receiving MessageId.ATTACHMENT packet.
     * Payload consists out of latest 50 public keys the sender transacted with.
     * This function parses the packet and increments the trust by 1 for every
     * key received.
     * Format is: '<key>, <key>, ..., <key>' where key is the hex-encoded public key.
     * @param packet : the corresponding packet that contains the right payload.
     */
    private fun onLastAddressPacket(packet: Packet) {
        try {
            val (peer, payload) =
                packet.getDecryptedAuthPayload(
                    TransactionsPayload.Deserializer,
                    myPeer.key as PrivateKey
                )

            Log.d("EuroTokenCommunity", "onLastAddressPacket received from peer: ${peer.key.keyToHash().toHex()}")

            Log.d("EuroTokenCommunity", "Raw payload data (hex): ${payload.data.toHex()}")

            val payloadString = String(payload.data, Charsets.UTF_8)
            Log.d("EuroTokenCommunity", "Decrypted payload content: $payloadString")

            if (payloadString.isBlank()) {
                Log.w("EuroTokenCommunity", "Payload is blank. Aborting trust update.")
                return
            }

            val addressesHex = payloadString.split(",")
            Log.d("EuroTokenCommunity", "Split addresses: $addressesHex")

            // initially ->String(payload.data).split(",").map { it.toByteArray() }
                // this caused errors using encoding
                // exchanging trust didnt work offline...
            val addresses: List<ByteArray> = addressesHex.map { it.hexToBytes() }
            Log.d("EuroTokenCommunity", "Decoded ${addresses.size} addresses from payload.")

            for (address in addresses) {
                Log.d("EuroTokenCommunity", "Calling incrementTrust for address: ${address.toHex()}")
                myTrustStore.incrementTrust(address)
            }
            Log.d("EuroTokenCommunity", "Finished processing trust addresses.")
        } catch (e: Exception) {
            Log.e("EuroTokenCommunity", "Error processing onLastAddressPacket", e)
        }
    }

    private fun onRollbackRequest(
        peer: Peer,
        payload: RollbackRequestPayload
    ) {
        transactionRepository.attemptRollback(peer, payload.transactionHash)
    }

    fun connectToGateway(
        public_key: String,
        ip: String,
        port: Int,
        payment_id: String
    ) {
        val key = defaultCryptoProvider.keyFromPublicBin(public_key.hexToBytes())
        val address = IPv4Address(ip, port)
        val peer = Peer(key, address)

        val payload = MessagePayload(payment_id)

        val packet =
            serializePacket(
                MessageId.GATEWAY_CONNECT,
                payload
            )

        send(peer, packet)
    }

    fun requestRollback(
        transactionHash: ByteArray,
        peer: Peer
    ) {
        val payload = RollbackRequestPayload(transactionHash)

        val packet =
            serializePacket(
                MessageId.ROLLBACK_REQUEST,
                payload
            )

        send(peer, packet)
    }

    object MessageId {
        const val GATEWAY_CONNECT = 1
        const val ROLLBACK_REQUEST = 1
        const val ATTACHMENT = 4
    }

    class Factory(
        private val store: GatewayStore,
        private val trustStore: TrustStore,
        private val context: Context,
    ) : Overlay.Factory<EuroTokenCommunity>(EuroTokenCommunity::class.java) {
        override fun create(): EuroTokenCommunity {
            return EuroTokenCommunity(store, trustStore, context)
        }
    }

    /**
     * Generate a public key based on the [seed].
     * @param seed : the seed used to generate the public key.
     */
    private fun generatePublicKey(seed: Long): String {
        // Initialize Random with seed
        val random = Random(seed)

        // Generate a random public key of 148 hexadecimal characters
        val key = random.nextBytes(148)
        return key.toHex()
    }

    /**
     * Generate [numberOfKeys] public keys based on the [seed].
     * @param numberOfKeys : the number of keys to generate.
     * @param seed : the seed used to generate the public keys.
     */
    private fun generatePublicKeys(
        numberOfKeys: Int,
        seed: Long = 1337
    ): List<String> {
        val publicKeys = mutableListOf<String>()
        for (i in 0 until numberOfKeys) {
            publicKeys.add(generatePublicKey(seed + i))
        }
        return publicKeys
    }

    /**
     * Called after the user has finished a transaction with the other party.
     * Sends the [num] public keys of latest transaction counterparties to the receiver.
     * When DEMO mode is enabled, it generates 50 random keys instead.
     * @param peer : the peer to send the keys to.
     * @param num : the number of keys to send.
     */
    fun sendAddressesOfLastTransactions(
        peer: Peer,
        num: Int = 50
    ) {
        Log.d("EuroTokenCommunity", "sendAddressesOfLastTransactions called for peer: ${peer.mid}")
        val pref = myContext.getSharedPreferences(EUROTOKEN_SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val demoModeEnabled = pref.getBoolean(DEMO_MODE_ENABLED, false)

        val addresses: ArrayList<String> = ArrayList()
        addresses.add(myPeer.publicKey.keyToBin().toHex())
        if (demoModeEnabled) {
            addresses.addAll(generatePublicKeys(num))
        } else {
            addresses.addAll(
                transactionRepository.getTransactions(num).map { transaction: Transaction ->
                    val counterpartyKey = if (transaction.outgoing) {
                        transaction.receiver
                    } else {
                        transaction.sender
                    }
                    counterpartyKey.keyToBin().toHex()
                }
            )
        }

        Log.d("EuroTokenCommunity", "Number of addresses to send: ${addresses.size}")
        Log.d("EuroTokenCommunity", "Addresses to send: $addresses")

        val payloadString = addresses.joinToString(separator = ",")
        Log.d("EuroTokenCommunity", "Payload string: $payloadString")
        val payload = TransactionsPayload(EVAId.EVA_LAST_ADDRESSES, payloadString.toByteArray())

        Log.d("EuroTokenCommunity", "Sending payload: $payloadString")

        val packet =
            serializePacket(
                MessageId.ATTACHMENT,
                payload,
                encrypt = true,
                recipient = peer
            )

        Log.d("EuroTokenCommunity", "Sending ATTACHMENT packet to ${peer.mid}")
        if (evaProtocolEnabled) {
            evaSendBinary(
                peer,
                EVAId.EVA_LAST_ADDRESSES,
                payload.id,
                packet
            )
        } else {
            send(peer, packet)
        }
    }

    /**
     * Collects trust addresses for offline exchange without sending them over the network.
     * This is used for NFC-based trust data exchange.
     *
     * @param count The number of addresses to collect
     * @return List of public key hex strings
     */
    fun collectTrustAddresses(count: Int = 50): List<String> {
        Log.d("EuroTokenCommunity", "Collecting $count trust addresses for offline exchange")

        val addresses = ArrayList<String>()

        // first have to include our own public key
        addresses.add(myPeer.publicKey.keyToBin().toHex())

        val pref = myContext.getSharedPreferences(
            EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(DEMO_MODE_ENABLED, false)

        if (demoModeEnabled) {
            // generate demo addresses
            // otherwise web of trust does not work..
            addresses.addAll(generatePublicKeys(count - 1))
        } else {
            try {
                // lets get the reference to the transaction repository
                // now in nfc manner we dont have a trustchaincommunity
                val transactions = transactionRepository.getTransactions(count)
                addresses.addAll(
                    transactions.map { transaction: Transaction ->
                        val counterpartyKey = if (transaction.outgoing) {
                            transaction.receiver
                        } else {
                            transaction.sender
                        }
                        counterpartyKey.keyToBin().toHex()
                    }.distinct().take(count - 1)
                )
            } catch (e: Exception) {
                Log.e("EuroTokenCommunity", "Error collecting transaction addresses", e)
            }
        }

        
        while (addresses.size < count) {
            addresses.add(generatePublicKey(addresses.size.toLong()))
        }

        Log.d("EuroTokenCommunity", "Collected ${addresses.size} trust addresses")
        return addresses.take(count)
    }

    /**
     * Processes trust addresses received via NFC.
     *
     * @param addressList List of public key hex strings
     */
    fun processTrustAddresses(addressList: List<String>) {
        Log.d("EuroTokenCommunity", "Processing ${addressList.size} trust addresses from NFC")

        addressList.forEach { addressHex ->
            try {
                // initially ->String(payload.data).split(",").map { it.toByteArray() }
                // this caused errors using encoding
                // exchanging trust didnt work offline....
                if (addressHex.isNotEmpty()) {
                    val addressBytes = addressHex.hexToBytes()
                    myTrustStore.incrementTrust(addressBytes)
                    Log.d("EuroTokenCommunity", "Incremented trust for address: $addressHex")
                }
            } catch (e: Exception) {
                Log.e("EuroTokenCommunity", "Error processing trust address: $addressHex", e)
            }
        }

        Log.d("EuroTokenCommunity", "Finished processing ${addressList.size} trust addresses")
    }
}

