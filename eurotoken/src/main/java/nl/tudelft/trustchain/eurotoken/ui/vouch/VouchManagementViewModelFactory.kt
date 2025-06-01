package nl.tudelft.trustchain.eurotoken.ui.vouch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.eurotoken.db.VouchStore

/**
 * Factory for creating VouchManagementViewModel with VouchStore and TransactionRepository dependencies.
 */
class VouchManagementViewModelFactory(
    private val vouchStore: VouchStore,
    private val transactionRepository: TransactionRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VouchManagementViewModel::class.java)) {
            return VouchManagementViewModel(vouchStore, transactionRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
