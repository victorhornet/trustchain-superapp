package nl.tudelft.trustchain.eurotoken.ui.vouch

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.eurotoken.db.VouchStore
import nl.tudelft.trustchain.eurotoken.entity.VouchEntry

class VouchManagementViewModel(
    private val vouchStore: VouchStore,
    private val transactionRepository: TransactionRepository
) : ViewModel() {
    private val _vouches = MutableLiveData<Map<String, VouchEntry>>(emptyMap())
    val vouches: LiveData<Map<String, VouchEntry>> = _vouches

    init {
        loadVouchesFromStore()
        // Clean up expired vouches on initialization
        vouchStore.cleanupExpiredVouches()
    }

    /**
     * Load vouches from persistent storage into LiveData.
     * This method loads all vouches made by the current user.
     */
    private fun loadVouchesFromStore() {
        viewModelScope.launch {
            try {
                val myKey = getMyPublicKey()
                val vouchList = vouchStore.getVouchesByVoucher(myKey)
                val vouchMap = vouchList.associateBy { it.pubKey.toHex() }
                _vouches.postValue(vouchMap)
            } catch (e: Exception) {
                // Handle error gracefully, keep empty map
                _vouches.postValue(emptyMap())
            }
        }
    }

    /**
     * Set or update a vouch for a specific user.
     * 
     * @param pubKey The public key of the user being vouched for
     * @param amount The vouch amount
     * @param until Optional expiration timestamp
     */
    fun setVouch(pubKey: ByteArray, amount: Double, until: Long? = null) {
        viewModelScope.launch {
            try {
                val myKey = getMyPublicKey()
                vouchStore.setVouch(myKey, pubKey, amount, until)
                loadVouchesFromStore() // Refresh the LiveData
            } catch (e: Exception) {
                // Handle error - could emit error state or show toast
            }
        }
    }

    /**
     * Update only the expiration time for an existing vouch.
     * 
     * @param pubKey The public key of the user being vouched for
     * @param until The new expiration timestamp
     */
    fun setVouchUntil(pubKey: ByteArray, until: Long?) {
        viewModelScope.launch {
            try {
                val myKey = getMyPublicKey()
                // Check if vouch exists first
                val existingVouch = vouchStore.getVouch(myKey, pubKey)
                if (existingVouch != null) {
                    vouchStore.updateVouchUntil(myKey, pubKey, until)
                    loadVouchesFromStore() // Refresh the LiveData
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Get a specific vouch for a user.
     * 
     * @param pubKey The public key of the user being vouched for
     * @return The vouch entry or null if not found
     */
    fun getVouch(pubKey: ByteArray): VouchEntry? {
        return try {
            val myKey = getMyPublicKey()
            vouchStore.getVouch(myKey, pubKey)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete a vouch for a specific user.
     * 
     * @param pubKey The public key of the user to remove vouch for
     */
    fun deleteVouch(pubKey: ByteArray) {
        viewModelScope.launch {
            try {
                val myKey = getMyPublicKey()
                vouchStore.deleteVouch(myKey, pubKey)
                loadVouchesFromStore() // Refresh the LiveData
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    /**
     * Get the current user's public key from the TrustChain community.
     * 
     * @return The current user's public key as ByteArray
     */
    private fun getMyPublicKey(): ByteArray {
        return transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin()
    }

    /**
     * Refresh vouches from storage.
     * Can be called to manually refresh the data.
     */
    fun refreshVouches() {
        loadVouchesFromStore()
    }
} 