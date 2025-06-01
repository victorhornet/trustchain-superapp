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
import nl.tudelft.trustchain.eurotoken.ui.settings.DefaultGateway

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
     * The [VouchStore] used to fetch and update vouch information from peers.
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
        myVouchStore = VouchStore.getInstance(context)
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
        private val context: Context,
    ) : Overlay.Factory<EuroTokenCommunity>(EuroTokenCommunity::class.java) {
        override fun create(): EuroTokenCommunity = EuroTokenCommunity(store, trustStore, context)
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
     * Called upon receiving MessageId.VOUCH_DATA packet.
     * Payload consists of vouch information from the sender.
     * This function parses the packet and updates the local vouch store with the received data.
     * Format is: '<voucher_key>:<vouchee_key>:<amount>:<until>, ...' where keys are hex-encoded.
     * @param packet : the corresponding packet that contains the vouch payload.
     */
    private fun onVouchDataPacket(packet: Packet) {
        val (_, payload) =
            packet.getDecryptedAuthPayload(
                VouchPayload.Deserializer,
                myPeer.key as PrivateKey
            )

        val vouchData = String(payload.data)
        if (vouchData.isNotEmpty()) {
            val vouchEntries = vouchData.split(",")
            for (entry in vouchEntries) {
                val parts = entry.split(":")
                if (parts.size >= 3) {
                    try {
                        val voucherKey = parts[0].hexToBytes()
                        val voucheeKey = parts[1].hexToBytes()
                        val amount = parts[2].toDouble()
                        val until = if (parts.size > 3 && parts[3].isNotEmpty()) parts[3].toLong() else null
                        
                        // Only update if we don't have this vouch or if received data is newer
                        val existingVouch = myVouchStore.getVouch(voucherKey, voucheeKey)
                        if (existingVouch == null) {
                            myVouchStore.setVouch(voucherKey, voucheeKey, amount, until)
                        }
                    } catch (e: Exception) {
                        // Skip malformed vouch entries
                        continue
                    }
                }
            }
        }
    }

    /**
     * Called after the user has finished a transaction with the other party.
     * Sends vouch information to the peer to keep vouch data synchronized across the network.
     * When DEMO mode is enabled, it generates sample vouch data instead.
     * @param peer : the peer to send the vouch data to.
     * @param maxEntries : the maximum number of vouch entries to send.
     */
    fun sendVouchData(
        peer: Peer,
        maxEntries: Int = 25
    ) {
        val pref = myContext.getSharedPreferences(EUROTOKEN_SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val demoModeEnabled = pref.getBoolean(DEMO_MODE_ENABLED, false)

        val vouchEntries: ArrayList<String> = ArrayList()
        
        if (demoModeEnabled) {
            // Generate sample vouch data for demo mode
            val random = Random(System.currentTimeMillis())
            for (i in 0 until maxEntries) {
                val voucherKey = generatePublicKey(1000L + i)
                val voucheeKey = generatePublicKey(2000L + i)
                val amount = (random.nextDouble() * 100).toString()
                val until = if (random.nextBoolean()) (System.currentTimeMillis() + 86400000L).toString() else ""
                vouchEntries.add("$voucherKey:$voucheeKey:$amount:$until")
            }
        } else {
            // Get actual vouch data from the store
            val myKey = myPeer.publicKey.keyToBin()
            val myVouches = myVouchStore.getVouchesByVoucher(myKey)
            
            // Convert vouch entries to string format
            for (vouch in myVouches.take(maxEntries)) {
                val voucherKey = myKey.toHex()
                val voucheeKey = vouch.pubKey.toHex()
                val amount = vouch.vouchAmount.toString()
                val until = vouch.vouchUntil?.toString() ?: ""
                vouchEntries.add("$voucherKey:$voucheeKey:$amount:$until")
            }
        }

        if (vouchEntries.isNotEmpty()) {
            val payload = VouchPayload(EVAId.EVA_VOUCH_DATA, vouchEntries.joinToString(separator = ",").toByteArray())

            val packet =
                serializePacket(
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
