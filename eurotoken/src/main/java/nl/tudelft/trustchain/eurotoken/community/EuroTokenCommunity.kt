package nl.tudelft.trustchain.eurotoken.community

import android.content.Context
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
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME
import nl.tudelft.trustchain.eurotoken.db.TrustStore
import nl.tudelft.trustchain.eurotoken.db.VouchStore
import nl.tudelft.trustchain.eurotoken.entity.Vouch
import nl.tudelft.trustchain.eurotoken.ui.settings.DefaultGateway
import java.util.*

class EuroTokenCommunity(
    store: GatewayStore,
    trustStore: TrustStore,
    vouchStore: VouchStore,
    context: Context,
) : Community() {
    override val serviceId = "f0eb36102436bd55c7a3cdca93dcaefb08df0750"

    private lateinit var transactionRepository: TransactionRepository

    /**
     * The [TrustStore] used to fetch and update trust scores from peers.
     */
    private var myTrustStore: TrustStore

    /**
     * The [VouchStore] used to fetch and update vouch data from peers.
     */
    private var myVouchStore: VouchStore

    /**
     * The context used to access the shared preferences.
     */
    private var myContext: Context

    init {
        messageHandlers[MessageId.ROLLBACK_REQUEST] = ::onRollbackRequestPacket
        messageHandlers[MessageId.ATTACHMENT] = ::onLastAddressPacket
        messageHandlers[MessageId.VOUCH_DATA] = ::onVouchDataPacket
        if (store.getPreferred().isEmpty()) {
            DefaultGateway.addGateway(store)
        }

        myTrustStore = trustStore
        myVouchStore = vouchStore
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
        val (_, payload) =
            packet.getDecryptedAuthPayload(
                TransactionsPayload.Deserializer,
                myPeer.key as PrivateKey
            )

        val addresses: List<ByteArray> = String(payload.data).split(",").map { it.toByteArray() }
        for (i in addresses.indices) {
            myTrustStore.incrementTrust(addresses[i])
        }
    }

    /**
     * Called upon receiving MessageId.VOUCH_DATA packet.
     * Payload consists of vouch data from the sender.
     * This function parses the packet and stores received vouches.
     * Format is JSON-encoded vouch data.
     * @param packet : the corresponding packet that contains the vouch payload.
     */
    private fun onVouchDataPacket(packet: Packet) {
        val (peer, payload) =
            packet.getDecryptedAuthPayload(
                VouchPayload.Deserializer,
                myPeer.key as PrivateKey
            )

        try {
            val vouchDataString = String(payload.data, Charsets.UTF_8)
            val vouchDataList = parseVouchData(vouchDataString)
            
            for (vouchData in vouchDataList) {
                val vouch = Vouch(
                    vouchedForPubKey = vouchData.vouchedForPubKey,
                    amount = vouchData.amount,
                    expiryDate = vouchData.expiryDate,
                    createdDate = vouchData.createdDate,
                    description = vouchData.description,
                    isActive = vouchData.isActive,
                    isReceived = true,
                    senderPubKey = peer.publicKey.keyToBin()
                )
                myVouchStore.addVouch(vouch)
            }
        } catch (e: Exception) {
            // Handle parsing errors gracefully
            println("Error parsing vouch data: ${e.message}")
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
        const val VOUCH_DATA = 5
    }

    class Factory(
        private val store: GatewayStore,
        private val trustStore: TrustStore,
        private val vouchStore: VouchStore,
        private val context: Context,
    ) : Overlay.Factory<EuroTokenCommunity>(EuroTokenCommunity::class.java) {
        override fun create(): EuroTokenCommunity {
            return EuroTokenCommunity(store, trustStore, vouchStore, context)
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
     * Parse vouch data from JSON string format.
     * Format: "vouched_for_key|amount|expiry_date|created_date|description|is_active;..."
     */
    private fun parseVouchData(vouchDataString: String): List<Vouch> {
        val vouches = mutableListOf<Vouch>()
        if (vouchDataString.isBlank()) return vouches

        val vouchEntries = vouchDataString.split(";")
        for (entry in vouchEntries) {
            if (entry.isBlank()) continue
            try {
                val parts = entry.split("|")
                if (parts.size >= 6) {
                    val vouch = Vouch(
                        vouchedForPubKey = parts[0].hexToBytes(),
                        amount = parts[1].toLong(),
                        expiryDate = Date(parts[2].toLong()),
                        createdDate = Date(parts[3].toLong()),
                        description = parts[4],
                        isActive = parts[5].toBoolean()
                    )
                    vouches.add(vouch)
                }
            } catch (e: Exception) {
                // Skip invalid entries
                continue
            }
        }
        return vouches
    }

    /**
     * Serialize vouches to string format.
     * Format: "vouched_for_key|amount|expiry_date|created_date|description|is_active;..."
     */
    private fun serializeVouchData(vouches: List<Vouch>): String {
        return vouches.joinToString(";") { vouch ->
            "${vouch.vouchedForPubKey.toHex()}|${vouch.amount}|${vouch.expiryDate.time}|${vouch.createdDate.time}|${vouch.description}|${vouch.isActive}"
        }
    }

    /**
     * Send vouch data to a peer.
     * @param peer : the peer to send the vouch data to.
     * @param num : the maximum number of vouches to send.
     */
    fun sendVouchData(
        peer: Peer,
        num: Int = 10
    ) {
        val vouches = myVouchStore.getOwnActiveVouches().take(num)
        val vouchDataString = serializeVouchData(vouches)

        val payload = VouchPayload(EVAId.EVA_VOUCH_DATA, vouchDataString.toByteArray(Charsets.UTF_8))

        val packet = serializePacket(
            MessageId.VOUCH_DATA,
            payload,
            encrypt = true,
            recipient = peer
        )

        // Send the vouch data to the peer using EVA
        if (evaProtocolEnabled) {
            evaSendBinary(
                peer,
                EVAId.EVA_VOUCH_DATA,
                payload.id,
                packet
            )
        } else {
            send(peer, packet)
        }
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
        val pref = myContext.getSharedPreferences(EUROTOKEN_SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val demoModeEnabled = pref.getBoolean(DEMO_MODE_ENABLED, false)

        val addresses: ArrayList<String> = ArrayList()
        // Add own public key to list of addresses.
        addresses.add(myPeer.publicKey.keyToBin().toHex())
        if (demoModeEnabled) {
            // Generate [num] addresses if in demo mode
            addresses.addAll(generatePublicKeys(num))
        } else {
            // Get all addresses of the last [num] incoming transactions
            addresses.addAll(
                transactionRepository.getTransactions(num).map { transaction: Transaction ->
                    transaction.sender.toString()
                }
            )
        }

        val payload = TransactionsPayload(EVAId.EVA_LAST_ADDRESSES, addresses.joinToString(separator = ",").toByteArray())

        val packet =
            serializePacket(
                MessageId.ATTACHMENT,
                payload,
                encrypt = true,
                recipient = peer
            )

        // Send the list of addresses to the peer using EVA
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
     * Every community initializes a different version of the EVA protocol (if enabled).
     * To distinguish the incoming packets/requests an ID must be used to hold/let through the
     * EVA related packets.
     */
    object EVAId {
        const val EVA_LAST_ADDRESSES = "eva_last_addresses"
        const val EVA_VOUCH_DATA = "eva_vouch_data"
    }
}
