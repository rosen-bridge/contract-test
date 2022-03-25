package rozen.bridge

import helpers.{Configs, Utils}
import org.ergoplatform.appkit._
import scorex.util.encode.Base16
import special.collection.Coll

import java.nio.ByteBuffer
import scala.collection.JavaConverters._
import scala.collection.mutable

class Lock {
  var sk = Utils.randBigInt
  var EWRBox: InputBox = null;
  var UTPBox: InputBox = null;
  var commitment: InputBox = null;

  def getUTP(): Array[Byte] = {
    return this.UTPBox.getTokens.get(0).getId.getBytes
  }

  def getUTPHex(): String = {
    return this.UTPBox.getTokens.get(0).getId.toString
  }

  def getProver(): ErgoProver = {
    Configs.ergoClient.execute(ctx => {
      ctx.newProverBuilder().withDLogSecret(sk.bigInteger).build()
    })
  }
}

class Commitment {
  var fromChain: String = "ADA"
  var toChain: String = "ERG"
  var fromAddress: String = ""
  var toAddress: String = ""
  var amount: Long = 100000
  var fee: Long = 2520
  var sourceChainTokenId: Array[Byte] = Base16.decode(Boxes.getRandomHexString()).get
  var targetChainTokenId: Array[Byte] = Base16.decode(Boxes.getRandomHexString()).get
  var sourceTxId: Array[Byte] = Base16.decode(Boxes.getRandomHexString()).get
  var sourceBlockId: Array[Byte] = Base16.decode(Boxes.getRandomHexString()).get

  def requestId(): Array[Byte] = {
    scorex.crypto.hash.Blake2b256(this.sourceTxId)
  }

  def partsArray(): Array[Array[Byte]] = {
    Array(
      this.fromChain.getBytes(),
      this.toChain.getBytes(),
      this.fromAddress.getBytes(),
      this.toAddress.getBytes(),
      ByteBuffer.allocate(8).putLong(this.amount).array(),
      ByteBuffer.allocate(8).putLong(this.fee).array(),
      this.sourceChainTokenId,
      this.targetChainTokenId,
      this.sourceTxId,
      this.sourceBlockId
    )
  }

  def hash(UTP: Array[Byte]): Array[Byte] = {
    scorex.crypto.hash.Blake2b256(Array(
      this.fromChain.getBytes(),
      this.toChain.getBytes(),
      this.fromAddress.getBytes(),
      this.toAddress.getBytes(),
      ByteBuffer.allocate(8).putLong(this.amount).array(),
      ByteBuffer.allocate(8).putLong(this.fee).array(),
      this.sourceChainTokenId,
      this.targetChainTokenId,
      this.sourceTxId,
      this.sourceBlockId,
      UTP
    ).reduce((a, b) => a ++ b))
  }
}

object Main {
  val sk = Utils.randBigInt
  var EWRId: String = ""
  var bank: InputBox = null
  var locks: Seq[Lock] = Seq()
  var triggerEvent: InputBox = null

  def getProver(): ErgoProver = {
    Configs.ergoClient.execute(ctx => {
      ctx.newProverBuilder().withDLogSecret(sk.bigInteger).build()
    })
  }

  def createBankBox(ctx: BlockchainContext, EWRCount: Long, Factor: Long, chainId: String): Unit = {
    val prover = getProver()
    val box1 = Boxes.createBoxForUser(
      ctx,
      prover.getAddress,
      1e9.toLong,
      new ErgoToken(Configs.tokens.BankNft, 1),
      new ErgoToken(Configs.tokens.RSN, 1)
    );
    EWRId = box1.getId.toString
    val bankOut = Boxes.createBankBox(ctx, EWRId, EWRCount, 1L, Seq(chainId.getBytes.toArray), Seq(0L), 0)
    val txB = ctx.newTxBuilder()
    val bankTxUnsigned = txB.boxesToSpend(Seq(box1).asJava)
      .fee(Configs.fee)
      .outputs(bankOut)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val bankTx = prover.sign(bankTxUnsigned)
    println(s"created $EWRId")
    bank = bankTx.getOutputsToSpend.get(0)
  }

  def lockRSN(ctx: BlockchainContext, RSNCount: Long): Unit = {
    val lock = new Lock();
    val EWRCount = RSNCount / 100L
    val prover = lock.getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(Configs.tokens.RSN, 10000L))
    val oldUsers = bank.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.toSeq.map(item => item.toArray)
    val users = oldUsers ++ Seq(bank.getId.getBytes)
    val oldUsersCount = bank.getRegisters.get(1).getValue.asInstanceOf[Coll[Long]].toArray.toSeq
    val usersCount = oldUsersCount ++ Seq(EWRCount)
    val bankOut = Boxes.createBankBox(
      ctx,
      EWRId,
      Boxes.getTokenCount(EWRId, bank) - EWRCount,
      Boxes.getTokenCount(Configs.tokens.RSN, bank) + EWRCount * 100L,
      users,
      usersCount,
      0
    )
    val lockBox = Boxes.createLockBox(ctx, EWRId, EWRCount, bank.getId.getBytes)
    val utp = Boxes.createBoxCandidateForUser(ctx, prover.getAddress, Configs.minBoxValue, new ErgoToken(bank.getId.getBytes, 1L))
    val lockTxUnsigned = ctx.newTxBuilder().boxesToSpend(Seq(bank, box1).asJava)
      .fee(Configs.fee)
      .outputs(bankOut, lockBox, utp)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val lockTx = prover.sign(lockTxUnsigned)
    bank = lockTx.getOutputsToSpend.get(0)
    lock.EWRBox = lockTx.getOutputsToSpend.get(1)
    lock.UTPBox = lockTx.getOutputsToSpend.get(2)
    println(s"locked ${lock.getUTPHex()}")
    locks = locks ++ Seq(lock)
  }

  def unlockRSN(ctx: BlockchainContext, EWRCount: Long, index: Int): Unit = {
    val lock = locks(index)
    val prover = lock.getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong)
    var R4 = bank.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.clone()
    val UTPIndex = R4.map(item => item.toArray).indexOf(lock.UTPBox.getTokens.get(0).getId.getBytes)
    var R5 = bank.getRegisters.get(1).getValue.asInstanceOf[Coll[Long]].toArray.clone()
    if (R5(UTPIndex) == EWRCount) {
      R4 = (R4.slice(0, UTPIndex).toSeq ++ R4.slice(UTPIndex + 1, R4.length)).toArray
      R5 = (R5.slice(0, UTPIndex).toSeq ++ R5.slice(UTPIndex + 1, R5.length)).toArray
      locks = locks.slice(0, index) ++ locks.slice(index + 1, locks.length)
    } else {
      R5.update(UTPIndex, R5(UTPIndex) - EWRCount)
    }
    val bankOut = Boxes.createBankBox(
      ctx,
      EWRId,
      Boxes.getTokenCount(EWRId, bank) + EWRCount,
      Boxes.getTokenCount(Configs.tokens.RSN, bank) - EWRCount * 100L,
      R4.toSeq.map(item => item.toArray),
      R5,
      UTPIndex
    )
    var candidates = Seq(bankOut)
    val boxes = Seq(bank, lock.EWRBox, lock.UTPBox, box1)
    val tokens = Boxes.calcTotalErgAndTokens(Seq(lock.EWRBox, lock.UTPBox, box1))
    tokens.update(Configs.tokens.BankNft, 0)
    tokens.update(EWRId, tokens.getOrElse(EWRId, 0L) - lock.EWRBox.getTokens.get(0).getValue)
    if (lock.EWRBox.getTokens.get(0).getValue > EWRCount) {
      val lockedOut = Boxes.createLockBox(ctx, EWRId, Boxes.getTokenCount(EWRId, lock.EWRBox) - EWRCount, lock.UTPBox.getTokens.get(0).getId.getBytes)
      candidates = candidates ++ Seq(lockedOut)
    }
    tokens.update(Configs.tokens.RSN, tokens.getOrElse(Configs.tokens.RSN, 0L) + EWRCount * 100)
    val totalErgIn: Long = tokens.getOrElse("", 0L)
    val userOutBuilder = ctx.newTxBuilder().outBoxBuilder()
      .contract(ctx.newContract(prover.getAddress.asP2PK().script))
      .value(totalErgIn - Configs.fee - bank.getValue)
    tokens.keys.foreach(tokenId => {
      val amount: Long = tokens.getOrElse(tokenId, 0)
      if (amount > 0 && tokenId != "") {
        userOutBuilder.tokens(new ErgoToken(tokenId, amount))
      }
    })
    candidates = candidates ++ Seq(userOutBuilder.build())
    val unlockTxUnsigned = ctx.newTxBuilder().boxesToSpend(boxes.asJava)
      .fee(Configs.fee)
      .outputs(candidates: _*)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val unlockTx = prover.sign(unlockTxUnsigned)
    //    println(unlockTx.toJson(true))
    println(s"unlocked: ${lock.getUTPHex()}")
    bank = unlockTx.getOutputsToSpend.get(0)
    lock.EWRBox = unlockTx.getOutputsToSpend.get(1)
    //    lock.UTPBox = unlockTx.getOutputsToSpend.get(2)
  }

  def redeemBank(ctx: BlockchainContext): Unit = {
    val prover = getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(Configs.tokens.GuardNFT, 1))
    val inputs = Seq(bank, box1)
    val boxBuilder = ctx.newTxBuilder().outBoxBuilder()
      .contract(ctx.newContract(prover.getAddress.asP2PK().script))
      .registers(
        bank.getRegisters.get(0),
        bank.getRegisters.get(1),
        bank.getRegisters.get(2),
      )
    boxBuilder.value(inputs.map(item => item.getValue).reduce((a, b) => a + b) - Configs.fee)
    inputs.foreach(box => box.getTokens.forEach(token => boxBuilder.tokens(token)))
    val txUnsigned = ctx.newTxBuilder().boxesToSpend(inputs.asJava)
      .fee(Configs.fee)
      .outputs(boxBuilder.build())
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    prover.sign(txUnsigned)
    println("guard operation succeed")
  }

  def createCommitment(ctx: BlockchainContext, commitment: Commitment, index: Int): Unit = {
    val lock = locks(index);
    val UTP = lock.getUTP()
    val box1 = Boxes.createBoxForUser(ctx, lock.getProver().getAddress, 1e9.toLong)
    val lockOut = Boxes.createLockBox(ctx, EWRId, Boxes.getTokenCount(EWRId, lock.EWRBox) - 1, UTP)
    val boxes = Seq(lock.EWRBox, lock.UTPBox, box1)
    val totalErg = boxes.map(item => item.getValue).reduce((a, b) => a + b)
    val userChange = Boxes.createBoxCandidateForUser(
      ctx,
      lock.getProver().getAddress,
      totalErg - Configs.fee - 2 * Configs.minBoxValue,
      new ErgoToken(UTP, 1)
    )
    val commitmentBox = Boxes.createCommitment(
      ctx,
      EWRId,
      lock.UTPBox.getTokens.get(0).getId.getBytes,
      commitment.requestId(),
      commitment.hash(UTP)
    )
    val commitmentUnsigned = ctx.newTxBuilder().boxesToSpend(boxes.asJava)
      .fee(Configs.fee)
      .sendChangeTo(lock.getProver().getAddress.getErgoAddress)
      .outputs(lockOut, commitmentBox, userChange)
      .build()
    val commitmentTx = lock.getProver().sign(commitmentUnsigned)
    lock.commitment = commitmentTx.getOutputsToSpend.get(1)
    lock.EWRBox = commitmentTx.getOutputsToSpend.get(0)
    println(s"user committed: ${lock.getUTPHex()}")
  }

  def redeemCommitment(ctx: BlockchainContext, index: Int): Unit = {
    val lock = locks(index);
    val prover = lock.getProver()
    val newLock = Boxes.createLockBox(ctx, EWRId, Boxes.getTokenCount(EWRId, lock.EWRBox) + 1, lock.getUTP())
    val inputs = Seq(lock.EWRBox, lock.UTPBox, lock.commitment)
    val redeemUnsigned = ctx.newTxBuilder().boxesToSpend(inputs.asJava)
      .fee(Configs.fee)
      .outputs(newLock)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val tx = prover.sign(redeemUnsigned)
    lock.EWRBox = tx.getOutputsToSpend.get(0)
    lock.commitment = null
  }

  def triggerEvent(ctx: BlockchainContext, index: Int, exclude: Seq[Int], commitment: Commitment): Unit = {
    val lock = locks(index)
    val processLocks = locks.zipWithIndex.collect {
      case (a, i) if !exclude.contains(i) => a
    }.filter(item => item != null)
    val commitments = processLocks.map(item => item.commitment).filter(item => item != null)
    val UTPs = processLocks.filter(item => item.commitment != null).map(item => item.getUTP())
    val trigger = Boxes.createTriggerEventBox(ctx, EWRId, UTPs, commitment)
    val box1 = Boxes.createBoxForUser(ctx, lock.getProver().getAddress, 1e9.toLong)
    val unsignedTx = ctx.newTxBuilder().boxesToSpend((commitments ++ Seq(box1)).asJava)
      .fee(Configs.fee)
      .outputs(trigger)
      .withDataInputs(Seq(bank).asJava)
      .sendChangeTo(lock.getProver().getAddress.getErgoAddress)
      .build()
    val tx = lock.getProver().sign(unsignedTx)
    triggerEvent = tx.getOutputsToSpend.get(0)
    processLocks.foreach(item => item.commitment = null)
    println(s"User Merged commitments ${lock.getUTPHex()}")
  }

  def guardPayment(ctx: BlockchainContext, commitment: Commitment): Unit = {
    val prover = getProver()
    val wBank = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(commitment.targetChainTokenId, commitment.fee))
    val UTPs = triggerEvent.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.map(item => item.toArray)
    val UTPHex = UTPs.map(item => Base16.encode(item))
    val notMergedLocks = locks.filter(item => item.commitment != null && !UTPHex.contains(item.getUTPHex()))
    val notMergedUTPs = notMergedLocks.map(item => item.getUTP())
    val userFee = commitment.fee / (UTPs.length + notMergedUTPs.length)
    val newLocks = (UTPs ++ notMergedUTPs).map(item => {
      Boxes.createLockBox(ctx, EWRId, 1, item, new ErgoToken(commitment.targetChainTokenId, userFee))
    })
    val inputs = Seq(triggerEvent) ++ notMergedLocks.map(item => item.commitment) ++ Seq(wBank)
    val unsignedTx = ctx.newTxBuilder().boxesToSpend(inputs.asJava)
      .fee(Configs.fee)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .outputs(newLocks: _*)
      .build()
    val tx = prover.sign(unsignedTx)
    notMergedLocks.foreach(item => item.commitment = null)
    println("guard payment completed")
    triggerEvent = null;
  }

  def mergeFraudToBank(ctx: BlockchainContext, fraud: InputBox): Unit ={
    val UTP = fraud.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray(0).toArray
    // TODO must merge one EWR to bank.
    println(s"one ${Base16.encode(UTP)} tokens slashed")
  }

  def moveToFraud(ctx: BlockchainContext): Unit ={
    val prover = getProver()
    val UTPs = triggerEvent.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.map(item => item.toArray)
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong)
    val newFraud = UTPs.map(item => {
      Boxes.createFraudBox(ctx, EWRId, item)
    })
    val unsignedTx = ctx.newTxBuilder().boxesToSpend(Seq(triggerEvent, box1).asJava)
      .fee(Configs.fee)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .outputs(newFraud: _*)
      .build()
    val tx = prover.sign(unsignedTx)
    println("fraud detected")
    triggerEvent = null;
    newFraud.indices.foreach(index => mergeFraudToBank(ctx, tx.getOutputsToSpend.get(index)))
  }

  def main(args: Array[String]): Unit = {
    Configs.ergoClient.execute(ctx => {
      createBankBox(ctx, 1e12.toLong, 100L, "ADA")
      (1 to 9).foreach(item => lockRSN(ctx, 10000L))
      unlockRSN(ctx, 50L, 2)
      unlockRSN(ctx, 50L, 2)
      redeemBank(ctx)
      val commitment = new Commitment()
      locks.indices.foreach(index => createCommitment(ctx, commitment, index))
      redeemCommitment(ctx, 0)
      triggerEvent(ctx, 1, Seq(5), commitment)
      guardPayment(ctx, commitment)
      locks.indices.foreach(index => createCommitment(ctx, commitment, index))
      triggerEvent(ctx, 0, Seq(), commitment)
      moveToFraud(ctx)
    })
  }
}
