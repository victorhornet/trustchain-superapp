# Added Features

## Advanced Trust Score Calculation

To provide flexibility and allow for future research, the EuroToken application includes multiple strategies for calculating and updating a user's trust score. While a simple linear increment is the default, more nuanced models are available to better reflect complex trust dynamics.

### Alternative Update Functions

The following alternative functions for updating trust scores are implemented and can be enabled:

* **Decay-weighted:** `score ← α·score + c (0 < α < 1)`
This model applies a decay factor to the current score before adding a constant. It means that past interactions contribute less to the score over time, making recent activity more significant.

* **Threshold Boost:** `if count ≥ N then score ← score + B`
This function provides a significant bonus (`B`) to a user's score once it reaches a certain threshold (`N`). It's useful for rewarding users who have demonstrated consistent positive behavior over a period of time.

* **Logistic Cap:** `score ← score + Δ·(1 – score/Max)`
This model provides diminishing returns. The increase in score (`Δ`) gets smaller as the user's score approaches the maximum possible score. This prevents scores from escalating too quickly and makes it progressively harder to reach the highest levels of trust.

### Current Implementation and Rationale

Currently, the system uses a **simple linear update** (`score ← score + 1`) as its default method for incrementing trust.

This approach was chosen as the default for its simplicity, predictability, and robustness. It provides a solid and easily understandable baseline for trust calculation that works well in a general context without requiring complex parameter tuning. The more advanced functions (Decay-weighted, Threshold Boost, and Logistic Cap) are included as experimental options for future development and research into more sophisticated trust models. However, if the developer wants, he can usae any of the porvided update functions and experiment with them.

## Double Spending Mitigation via Automated Collateral

To address the risk of double-spending in an offline environment, EuroToken implements a system of collateralized transactions, or "bonds". This mechanism ensures that a user who receives money while offline is compensated even if the sender later double-spends those funds. The system is designed to be automatic and punitive, discouraging malicious behavior.

### The Concept

When a user (Alice) wants to transact with another user (Bob) who may be offline, Alice must first lock a certain amount of her own funds as a "bond". This bond serves as collateral for the transactions she intends to make with Bob. If Alice acts maliciously by double-spending the funds she sent to Bob, her locked bond is automatically forfeited to Bob upon detection.

### Bond Types and Interaction

The EuroToken system supports different types of bonds to handle various trust scenarios:

* **Long-Term Bonds:** These can be established between users who transact frequently (e.g., a customer and a local shop). A user might lock a larger, standing bond with a trusted peer to represent a general line of credit or trust, facilitating multiple smaller transactions without the need for new collateral each time.
* **One-Shot Bonds:** These are designed specifically for single, higher-risk interactions, such as a one-off transaction with an unknown or offline peer. They are single-use and are tied to a specific, impending transaction.

The double-spend mitigation feature exclusively utilizes **One-Shot Bonds** to ensure the penalty is precise and contained. When a transaction is rolled back due to a double-spend, the system specifically searches for an active, `one-shot` bond created by the sender (Alice) for the receiver (Bob) just before the fraudulent transaction took place. It does *not* affect any long-term bonds that might exist between them. This surgical approach ensures that only the collateral for the specific malicious act is forfeited, leaving general trust arrangements intact.

### How It Works

1. **Creating a Bond**: Before transacting with a potentially offline Bob, Alice creates a **one-shot bond**. This action locks a specified amount from Alice's balance, dedicating it as single-use collateral for her upcoming transaction with Bob.

2. **Offline Transaction**: Alice can now send funds to Bob, even if Bob is offline. Bob's wallet receives and provisionally accepts the transaction, knowing it is secured by Alice's specific one-shot bond.

3. **Reconnection and Reconciliation**: When Bob's device comes back online, it syncs with the EuroToken network to validate all the transactions it received while offline.

4. **Detecting a Double-Spend**: If Alice was malicious, she might have spent the same funds in another transaction that has already been confirmed on the network. When Bob's device submits its transaction from Alice, the network detects the conflict and initiates a "rollback".

5. **Automatic Forfeiture**: The rollback of the transaction is the trigger for the penalty. The EuroToken application on Bob's device automatically detects that the transaction from Alice failed. It then finds the active **one-shot bond** Alice created and changes its status to `FORFEITED`. This action permanently subtracts the locked bond amount from Alice's balance, effectively compensating Bob for the fraudulent transaction.

This automated collateral system ensures that even in a completely offline, peer-to-peer scenario, there is a strong economic disincentive against double-spending, thereby maintaining the integrity and trustworthiness of the currency.
