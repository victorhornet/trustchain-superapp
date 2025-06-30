# Added Features (Overview)

## 1. Advanced Trust Score Calculation

To provide flexibility and allow for future research, the EuroToken application includes multiple strategies for calculating and updating a user's trust score. While a simple linear increment is the default, more nuanced models are available to better reflect complex trust dynamics.
**ExpectedCoverage (Cexp) =
Rp × min(B, A)

∑ [ Ri × min(Li, A) ] over all guarantors i
Shortfall (S) = max(0, A - Cexp)
Lambda (λ) = baseLambda × (1 + γ × (S / A))
Confidence = exp( -λ × (S / A) )
where
A = transaction amount
B = payer’s available balance
Rp = normalized trust score of the payer (e.g. via sigmoid)
Ri = normalized trust score of guarantor i
Li = loan limit (or available balance) of guarantor i, i.e, the amount vouched by the guarantor.**
The formula calculates the likelihood of payment using user's and guarantors' trust scores. Next, the percentage of shortfall r deficit with respect to the amount is being calculated. Next, sensitivity lambda is measured using baseLambda, gamma and uncovered portion. BaseLambda is used to make sure that some caution is applied even when coverage is full. Gamma controls how strongly we penalize a shortfall. Higher deficit results in higher penalty. To demonstrate decay of confidence with rise of Lambda, the exponential formula is used. This structure ensures that larger shortfalls not only reduce confidence directly, but also increase the rate at which confidence drops.

In the previous work relevant to controlling double spending, a random walk was initiated to find lenders who are willing to vouch for lending money. For now, we have preserved a list of guarantors for each user who are willing to vouch for loaning. The obtained confidence score will be used for updating trust scores of users. Currently lambda controls how bad the shortfall is and it is being set dynamically. We consider constant values for update parameters alpha and gamma. The provided risk estimation formula is preventive against risky or overpromising transactions, which is essential in offline or trust-based systems.



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

This approach was chosen as the default for its simplicity, predictability, and robustness. It provides a solid and easily understandable baseline for trust calculation that works well in a general context without requiring complex parameter tuning. The more advanced functions (Decay-weighted, Threshold Boost, and Logistic Cap) are included as experimental options for future development and research into more sophisticated trust models. However, if the developer wants, he can use any of the provided update functions and experiment with them.

## 2. Double Spending Mitigation via Automated Collateral

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


# EuroToken Bond and Risk Management Features (Details)

## 1. Bond Mechanism

### Overview

**Bonds** are locked amounts of collateral managed between users, backing vouches or transactions. They provide a security mechanism for the trust network.

### Types

* **Long-Term Bonds:** Standby collateral for frequent, trusted peers (e.g., local merchant).
* **One-Shot Bonds:** Single-use, tied to a specific high-risk or offline transaction.

### Key Components

**Database Layer**

* Table: `bonds` (see `BondStore.kt`)
* Fields: id, amount, lender/receiver pub keys, createdAt, expiredAt, status, is\_one\_shot

**Data Model**

* `Bond.kt`

    * Fields: id, amount, publicKeyLender, publicKeyReceiver, createdAt, expiredAt, transactionId, status (ACTIVE, RELEASED, FORFEITED, CLAIMED, EXPIRED), purpose, isOneShot

**Core Logic**

* **Bond creation** (usually by lender): checks available balance, sets bond as `ACTIVE`.
* **Bond status updates:** `ACTIVE` → `RELEASED` (successful claim), `FORFEITED` (double-spend), `EXPIRED`.
* **Cleanup:** Expired bonds are periodically cleaned from storage.

**Key Methods in `BondStore.kt`**

* `setBond(bond: Bond)`
* `getActiveBondsBetween(lender, receiver)`
* `updateBondStatus(bondId, status)`
* `cleanupExpiredBonds()`

**Bond Claiming Process**

* After successful loan/payout, system checks for active bonds from guarantors to borrower and releases (claims) them.
* On double-spending/rollback, only the specific one-shot bond is forfeited.

---

## 2. Risk Estimation

### Overview

A **risk estimator** calculates the likelihood that a user (and their guarantors) will successfully fulfill a transaction, using trust scores and vouch amounts.

**Implemented in**: `RiskEstimator.kt`

### Risk Estimation Formula

```
ExpectedCoverage (Cexp) = Rp × min(B, A) + ∑ [ Ri × min(Li, A) ]
Shortfall (S) = max(0, A - Cexp)
Lambda (λ) = baseLambda × (1 + γ × (S / A))
Confidence = exp(-λ × (S / A))
```

Where:

* A = transaction amount
* B = payer’s balance
* Rp = normalized trust score of payer
* Ri = normalized trust score of guarantor i
* Li = amount vouched by i

**Higher shortfall = higher penalty = lower confidence.**

**Key Function:**

<pre> ```kotlin fun riskEstimationFunction(amount: Long, payerPublicKey: ByteArray): Double ``` </pre>

**Parameters:** Base lambda and gamma tunable; supports trust-based penalty and shortfall response.

---

## 3. Double Spending Mitigation

### Overview

**Automatic collateral slashing** prevents and punishes double-spending, especially when parties are offline.

### How It Works

1. **One-Shot Bond**: Sender creates bond for the recipient prior to transaction.
2. **Transaction Occurs**: (possibly while recipient is offline)
3. **If double-spend detected (rollback):**

    * The *specific* one-shot bond for that transaction is **forfeited** (slashed)
    * Trust score of the offending user is **decremented**
    * No impact on long-term bonds

**Code:**

* Core logic in `EuroTokenCommunity.kt`'s `handleTransactionRollback()`
* Bond status update in `BondStore.kt`

---

## 4. Trust Scores

### Overview

**Trust scores** represent each user's reliability, and are updated on successful or failed transactions, vouching, and bond claims/forfeits.

**Data Model**

* `TrustScore.kt`

    * pubKey, trust (integer)

**Updating Methods**

* Increment/decrement trust on relevant events
* Supports advanced strategies (linear, decay, threshold, logistic cap) as options

---

## 5. Guarantors

### Overview

**Guarantors** are users who vouch for another's transaction, backing it with trust and (optionally) locked collateral.

**Data Model**

* `Guarantor.kt`

    * publicKey, trust (score), vouchAmount, vouchUntil

**Usage**

* Used in risk estimation and loan payout
* Only active and unexpired vouches considered

---

## 6. Testing and Validation
*Current Status*

1. The code for bond calculation and risk estimation is implemented and integrated into the vouch creation flow.

2. Risk estimation is triggered during vouch creation, ensuring only feasible bonds are created based on trust scores and available balances.

3. Initial checks confirm that risk estimation is being performed as intended during vouch creation.

4. Full testing of the bond claiming process—especially for scenarios involving multiple users in coordination—remains pending, as it requires a distributed environment to simulate real-world activity.

5. End-to-end vouch creation and bond claiming flows may encounter issues, particularly when interacting with newly joined users or in less synchronized network conditions.

6. Further validation of the complete process—including vouching, risk assessment, transaction execution, and bond claiming—will continue as the testing environment is expanded and refined.

*Potential Difficulties and Testing Challenges*

1. Multi-party Synchronization: Coordinating risk estimation, vouch creation, and bond claiming requires reliable interactions among several peers; real-world network and device states may introduce edge cases.

2. Onboarding of New Devices: Newly joined users or devices may encounter delays or issues in vouch propagation, leading to possible trust or bond inconsistencies at first use.

3. Transaction Propagation: Ensuring that all relevant parties receive and react to changes (such as bond status or vouch activation) is complex in a decentralized setup.

4. State Consistency: Simultaneous actions by multiple users may result in race conditions or timing issues in bond claiming and state updates.

5. Testing at Scale: Certain edge cases—such as double-spend detection or bond forfeiture—require concurrent multi-party activity to observe and fully validate.

## 7. Future Enhancements

* Automated bond and vouch  handling
* Complex flow of bond and on-reconnect trigger properly managed
* Smart contract integration for collateral management
* Reputation scoring
* Enhanced UI for bond and trust network visualization

