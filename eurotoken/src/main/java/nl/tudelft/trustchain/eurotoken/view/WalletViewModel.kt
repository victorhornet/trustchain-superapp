package nl.tudelft.trustchain.eurotoken.view

import androidx.lifecycle.*
import nl.tudelft.ipv8.keyvault.PublicKey
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import kotlinx.coroutines.launch
import java.util.Date

class WalletViewModel(
    private val transactionRepository: TransactionRepository
) : ViewModel() {
    private val _balance = MutableLiveData<Long>()
    val balance: LiveData<Long> get() = _balance

    private val _lockedBalance = MutableLiveData<Long>()
    val lockedBalance: LiveData<Long> get() = _lockedBalance

    private val _spendableBalance = MutableLiveData<Long>()
    val spendableBalance: LiveData<Long> get() = _spendableBalance

    // TODO can potentailly be deleted
    // might be handy for later transactions
    // now solely for test
    init {
        // load initial balance when ViewModel is created
        refreshBalances()
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

    suspend fun createBond(amount: Long, expiresAt: Date, purpose: String): Long? {
        val bondId = transactionRepository.createBond(amount, expiresAt, purpose)
        if (bondId != null) {
            refreshBalances()
        }
        return bondId
    }

    suspend fun releaseBond(bondId: Long): Boolean {
        val success = transactionRepository.releaseBond(bondId)
        if (success) {
            refreshBalances()
        }
        return success
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
