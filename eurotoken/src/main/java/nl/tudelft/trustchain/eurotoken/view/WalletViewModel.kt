package nl.tudelft.trustchain.eurotoken.view

import androidx.lifecycle.*
import nl.tudelft.ipv8.keyvault.PublicKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import kotlinx.coroutines.launch
import java.util.Date
import java.math.BigInteger

class WalletViewModel(
    private val transactionRepository: TransactionRepository,
    private val trustChainCommunity: TrustChainCommunity,
    private val bondStore: BondStore
) : ViewModel() {
    private val _balance = MutableLiveData<Long>()
    val balance: LiveData<Long> get() = _balance

    private val _lockedBalance = MutableLiveData<Long>()
    val lockedBalance: LiveData<Long> get() = _lockedBalance

    private val _spendableBalance = MutableLiveData<Long>()
    val spendableBalance: LiveData<Long> get() = _spendableBalance

    private val _activeBonds = MutableLiveData<List<Bond>>()
    val activeBonds: LiveData<List<Bond>> = _activeBonds

    // TODO can potentailly be deleted
    // might be handy for later transactions
    // now solely for test
    init {
        // load initial balance when ViewModel is created
        refreshBalances()
        refreshActiveBonds()
    }

    fun getPublicKey(): PublicKey {
        return defaultCryptoProvider.keyFromPublicBin(
            transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin()
        )
    }

    fun refreshBalances() {
        viewModelScope.launch {
            _balance.value = transactionRepository.getMyBalance()
            _lockedBalance.value = transactionRepository.getMyLockedBalance()
            _spendableBalance.value = transactionRepository.getMySpendableBalance()
        }
    }

    private fun refreshActiveBonds() {
        viewModelScope.launch {
            val myPublicKey = trustChainCommunity.myPeer.publicKey.keyToBin()
            val bonds = bondStore.getActiveBonds(myPublicKey)
            _activeBonds.postValue(bonds)
        }
    }

    fun createBond(amount: Long, expiresAt: Date, purpose: String): String? {
        val bondId = transactionRepository.createBond(amount, expiresAt, purpose)
        if (bondId != null) {
            refreshBalances()
            refreshActiveBonds()
        }
        return bondId
    }

    fun releaseBond(bondId: String): Boolean {
        val success = transactionRepository.releaseBond(bondId)
        if (success) {
            refreshBalances()
            refreshActiveBonds()
        }
        return success
    }

    fun forfeitBond(bondId: String): Boolean {
        val success = transactionRepository.forfeitBond(bondId)
        if (success) {
            refreshBalances()
            refreshActiveBonds()
        }
        return success
    }

    fun checkForDoubleSpends(): List<String> {
        val doubleSpentBonds = transactionRepository.checkForDoubleSpends()
        if (doubleSpentBonds.isNotEmpty()) {
            refreshBalances()
            refreshActiveBonds()
        }
        return doubleSpentBonds
    }

    fun sendAmount(amount: Int, recipientPK: PublicKey, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val spendableBalance = transactionRepository.getMySpendableBalance()
                if (amount.toLong() > spendableBalance) {
                    onComplete(false)
                    return@launch
                }

                val success = transactionRepository.sendTransferProposal(
                    recipientPK.keyToBin(),
                    amount.toLong()
                )

                if (success) {
                    refreshBalances()
                }

                onComplete(success)
            } catch (e: Exception) {
                // Log error and notify caller
                // TODO
                onComplete(false)
            }
        }
    }

    // noa callback
    suspend fun sendAmount(amount: Int, recipientPK: PublicKey): Boolean {
        return try {
            val spendableBalance = transactionRepository.getMySpendableBalance()
            if (amount.toLong() > spendableBalance) {
                return false
            }

            val result = transactionRepository.sendTransferProposal(
                recipientPK.keyToBin(),
                amount.toLong()
            )

            if (result) {
                refreshBalances()
            }

            result
        } catch (e: Exception) {
            // should probably log this somewhere
            false
        }
    }

    // transaction history?
}
