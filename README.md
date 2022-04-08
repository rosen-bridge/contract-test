# Rosen Bridge
Rosen bridge is an Ergo-centric bridge enabling users to send and receive coins and tokens between Ergo and any other chain. In this document, we will explain the Rosen bridge design technically. You may also want to read about Rosen bridge's high-level concepts [here](https://github.com/mhssamadani/RosenBridge).

## Bridge Components
Before explaining the main idea and procedures, it's better to get familiar with its components and concepts:

### Roles
1. **Guard:** A guard is a trusted party performing final actions in the system. Actually, a set of trusted guards are needed to transfer money between chains. The guard set is a small set including real parties. Each guard individually verifies the events and performs the required action. However, all guards should agree on one event to make the final operation.
2. **Watcher:** As the name suggests, watchers are some volunteer participants who watch two chains to create the transfer events. After a quorum of watchers reported the same event, a watcher will make a trigger event for guards. Watchers are not trusted and may have faults, intentionally or unintentionally. Anyway, the guilty watcher will be fined for his faulty action, and on the other hand, honest watchers get rewards.

### Tokens
1. **Rosen Bridge Token (RSN):** The Rosen bridge token defines the bridge participation rights. Each person who has a portion of this token can participate in the bridge as a watcher. 
2. **Ergo Watcher Rosen Token (EWR):** Each watcher volunteer needs to lock his RSN tokens to be a watcher of a specific bridge. The legitimate watchers receive EWR tokens on behalf of their RSN. Each of these tokens can be used to create new events, and if the event were true, guards would return the EWR to the watcher. EWR is a chain-specific token; thus, for each new bridge to other networks, we have different EWR tokens.
3. **Guard NFT:** This is a guard identifier token. This token is locked in a multi-sig address; thus, N out of M guards must sign this contract to spend it.
4. **Cleanup NFT:** This token is the cleanup service identifier.
5. **User Payment Token (UPT):** After locking RSNs by a volunteer, a new token is issued and paid to the watcher as well as the EWR tokens. A watcher may have multiple EWR tokens, enabling him to create different events on one bridge simultaneously, but UPT is a unique token that identifies the watcher. All bridge reward payments are also spendable by the owner of this unique token.


### Contracts
1. **Watcher Bank**: This contract is the system configuration for each chain. This contract is responsible for tracking the corresponding EWR tokens and locking RSNs to emit new EWRs. It also stores the EWR to RSN factor, the quorum percentage of watchers, and the maximum watcher count for this chain. 
2. **Watcher Lock**: When a new watcher is registered, his EWR tokens would be locked in this contract. It also stores the UPT id in the registers; the watcher must use his UPT as his authentication in every new event generation, reward reception, or EWR token redemption actions.
3. **Watcher Commitment**: When a watcher detects a new event on the target chain creates a commitment.
4. **Trigger Event**: A watcher creates the trigger event after a quorum of watchers reported the same event. He will spend all commitments and reveals the event contents to generate the trigger event. It also stores the reporter UPT ids in the trigger event that the guards will process.
5. **Watcher Fraud Lock**: Some watchers may create faulty events. The faulty events will be processed by the guards if a quorum of watchers created that event (resulting in a trigger event), but guards will ignore these triggers since they are not verifiable. After a while, the cleanup service collects all these faulty events and slashes their EWR tokens in the fraud lock as punishment.

### Data
1. **Event**: Any payment request from one chain to the other one. Event contains:
   1. Source tx id
   2. From chain
   3. To chain
   4. From address
   5. To address
   6. Amount
   7. Fee
   8. Source chain token id
   9. Target chain token id
   10. Source block id

2. **Commitment:** The event report was created by the watchers. The commitment contains:
   1. User UPT id
   2. Commitment (hash of the event content concatenated by the UPT id)
   3. Event id (hash of related txId on source chain)

## Rosen Bridge Life Cycle
In this section, we will review the introduced procedures, stored data, and component dependence in detail. These phases are the life cycle of a bridge, but the Rosen bridge is the collection of different such bridges connecting Ergo to the outside world.

### Phase 1: Locking RSN
As mentioned earlier, each watcher volunteer needs to lock his RSN tokens to receive corresponding EWRs. so in the locking transaction, these requirements must be satisfied:
* Bank box data should be updated:
	 * Append his UPT id to the UPT list
	 * Append the number of receiving EWR tokens to the token count list
	 * based on EWR/RSN factor, pay RSN to the bank and get the EWRs.
* A locked box is created containing all EWR tokens. It also stores the UPT id (bank boxId) in its registers.
* Issue UPT token with input bank boxId and send it to the watcher address.

<p align="center">
<img src="Images/Lock.png">
</p>

### Phase 2: Commitment

Each watcher observes both chains and, in case of detecting any valid transfer requests, creates the event commitment in the Ergo network. It also stores the request information in its local database for future usage.
In this transaction:
* Inputs are:
   * Locked box containing EWR
   * A box containing his UPT as its first token
   * (Optional) Any other locked box in the network can be merged in this transaction (See Phase 4)
* Only one new Locked box is created so that all remaining EWRs will be aggregated.
* The created commitment box has exactly one EWR. It also stores all commitment information in its registers.

<p align="center">
<img src="Images/Commitment.png">
</p>


### Phase 3: Commitment Redeem
At any time watcher can spend the commitment and redeem his EWR if the trigger event is not created using that commitment.
This simple transaction only spends the commitment box and creates a locked box containing EWR. Besides, user UPT must exist in the second input as the user authenticator.

<p align="center">
<img src="Images/CommitmentRedeem.png">
</p>

### Phase 4: Commitment Reveal

In this phase, a watcher creates the trigger event after a quorum of watchers reported the same event. He will spend all commitments and reveals the event contents to generate the trigger event.
At first, the watcher finds all commitments with the same id. Then, he verifies the commitments with the event data and the issuer's UPT id. Finally, if there are sufficient commitments, he spends them all and creates the trigger event. Created trigger event stores:
* List of all UPT ids merged in this transaction
* Event data

<p align="center">
<img src="Images/TriggerEvent.png">
</p>

## Phase 5: Guard Payment Process

Guards process all trigger events, and if the corresponding payment transaction has received enough confirmation on the source chain, they verify the request information.
After a quorum of guards verifications (M out of N guards), they create the target chain transaction paying the tokens to the user.
Finally, the final reward distribution transaction will be done after the payment transaction receives the required confirmation on the target chain. In this transaction:
* Inputs are:
	* Trigger Event
	* Any valid commitment not merged in creating the trigger event
	* wrapped token bank to pay the transaction fee
* The reward is distributed between all watchers who created the valid commitment, either merged on the trigger event or spent individually in this transaction. Thus, for each mentioned UPT id, a locked box with the reward share and one EWR is generated.
* Guards receive their reward share
* The payment transaction id on the target chain is stored in the first box for future audits.
<p align="center">
<img src="Images/Payment.png">
</p>

All payment process explanations in this section are in the status where Ergo is the source chain. In the opposite direction, the process is similar, but the user payment transaction is done within the reward distribution. So, in that case, one output box is added to the above transaction.

### Phase 5': Fraud Detection
If a quorum of guards couldn't verify a trigger event, they would ignore that event. After a while, the cleanup service spends this trigger event and slashes the collected EWR tokens (or correspondent RSNs) as the fault penalty.
This process is done in two steps:

1. **Create Fraud boxes**: cleanup service spends the trigger event and generates fraud boxes for each recorded UPT. Each fraud box contains precisely one EWR.
<p align="center">
<img src="Images/MoveFraud.png">
</p>

2. **Redeem Fraud boxes**: Spend all created fraud boxes, then redeem and slash their RSN tokens.
<p align="center">
<img src="Images/SlashToken.png">
</p>

The cleanup service box with its unique token should exist in both transactions.

### Phase 6: Watcher Reward Collection
As mentioned in Phase 5, guards will pay back the EWR token besides the watcher reward after accepting the trigger events. A watcher can spend all these boxes with his UPT as the authentication. He may want to create the new events or merge these boxes and extract the collected rewards.
