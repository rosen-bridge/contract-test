package rosen.bridge

import helpers.{Configs, Utils}
import org.ergoplatform.appkit._
import scorex.util.encode.Base16
import special.collection.Coll

import java.nio.ByteBuffer
import scala.collection.JavaConverters._
import scala.collection.mutable

class Permit {
  var sk = Utils.randBigInt
  var EWRBox: InputBox = null;
  var WIDBox: InputBox = null;
  var commitment: InputBox = null;

  def getWID(): Array[Byte] = {
    this.WIDBox.getTokens.get(0).getId.getBytes
  }

  def getWIDHex(): String = {
    this.WIDBox.getTokens.get(0).getId.toString
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
      this.sourceTxId,
      this.fromChain.getBytes(),
      this.toChain.getBytes(),
      this.fromAddress.getBytes(),
      this.toAddress.getBytes(),
      ByteBuffer.allocate(8).putLong(this.amount).array(),
      ByteBuffer.allocate(8).putLong(this.fee).array(),
      this.sourceChainTokenId,
      this.targetChainTokenId,
      this.sourceBlockId
    )
  }

  def hash(WID: Array[Byte]): Array[Byte] = {
    scorex.crypto.hash.Blake2b256(Array(
      this.sourceTxId,
      this.fromChain.getBytes(),
      this.toChain.getBytes(),
      this.fromAddress.getBytes(),
      this.toAddress.getBytes(),
      ByteBuffer.allocate(8).putLong(this.amount).array(),
      ByteBuffer.allocate(8).putLong(this.fee).array(),
      this.sourceChainTokenId,
      this.targetChainTokenId,
      this.sourceBlockId,
      WID
    ).reduce((a, b) => a ++ b))
  }
}

object Main {
  val sk = Utils.randBigInt
  var EWRId: String = ""
  var repo: InputBox = null
  var permits: Seq[Permit] = Seq()
  var triggerEvent: InputBox = null

  def getProver(): ErgoProver = {
    Configs.ergoClient.execute(ctx => {
      ctx.newProverBuilder().withDLogSecret(sk.bigInteger).build()
    })
  }

  def createRepoBox(ctx: BlockchainContext, EWRCount: Long, Factor: Long, chainId: String): Unit = {
    val prover = getProver()
    val box1 = Boxes.createBoxForUser(
      ctx,
      prover.getAddress,
      1e9.toLong,
      new ErgoToken(Configs.tokens.RepoNFT, 1),
      new ErgoToken(Configs.tokens.RSN, 1)
    );
    EWRId = box1.getId.toString
    val repoOut = Boxes.createRepo(ctx, EWRId, EWRCount, 1L, Seq(chainId.getBytes.toArray), Seq(0L), 0)
    val txB = ctx.newTxBuilder()
    val repoTxUnsigned = txB.boxesToSpend(Seq(box1).asJava)
      .fee(Configs.fee)
      .outputs(repoOut)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val repoTx = prover.sign(repoTxUnsigned)
    println(s"created $EWRId")
    repo = repoTx.getOutputsToSpend.get(0)
  }

  def getPermit(ctx: BlockchainContext, RSNCount: Long): Unit = {
    val permit = new Permit();
    val EWRCount = RSNCount / 100L
    val prover = permit.getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(Configs.tokens.RSN, 10000L))
    val oldUsers = repo.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.toSeq.map(item => item.toArray)
    val users = oldUsers ++ Seq(repo.getId.getBytes)
    val oldUsersCount = repo.getRegisters.get(1).getValue.asInstanceOf[Coll[Long]].toArray.toSeq
    val usersCount = oldUsersCount ++ Seq(EWRCount)
    val repoOut = Boxes.createRepo(
      ctx,
      EWRId,
      Boxes.getTokenCount(EWRId, repo) - EWRCount,
      Boxes.getTokenCount(Configs.tokens.RSN, repo) + EWRCount * 100L,
      users,
      usersCount,
      0
    )
    val permitBox = Boxes.createPermitBox(ctx, EWRId, EWRCount, repo.getId.getBytes)
    val utp = Boxes.createBoxCandidateForUser(ctx, prover.getAddress, Configs.minBoxValue, new ErgoToken(repo.getId.getBytes, 1L))
    val getPermitTxUnsigned = ctx.newTxBuilder().boxesToSpend(Seq(repo, box1).asJava)
      .fee(Configs.fee)
      .outputs(repoOut, permitBox, utp)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val getPermitTx = prover.sign(getPermitTxUnsigned)
    repo = getPermitTx.getOutputsToSpend.get(0)
    permit.EWRBox = getPermitTx.getOutputsToSpend.get(1)
    permit.WIDBox = getPermitTx.getOutputsToSpend.get(2)
    println(s"permit ${permit.getWIDHex()}")
    permits = permits ++ Seq(permit)
  }

  def returnPermit(ctx: BlockchainContext, EWRCount: Long, index: Int): Unit = {
    val permit = permits(index)
    val prover = permit.getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong)
    var R4 = repo.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.clone()
    val WIDIndex = R4.map(item => item.toArray).indexOf(permit.WIDBox.getTokens.get(0).getId.getBytes)
    var R5 = repo.getRegisters.get(1).getValue.asInstanceOf[Coll[Long]].toArray.clone()
    if (R5(WIDIndex) == EWRCount) {
      R4 = (R4.slice(0, WIDIndex).toSeq ++ R4.slice(WIDIndex + 1, R4.length)).toArray
      R5 = (R5.slice(0, WIDIndex).toSeq ++ R5.slice(WIDIndex + 1, R5.length)).toArray
      permits = permits.slice(0, index) ++ permits.slice(index + 1, permits.length)
    } else {
      R5.update(WIDIndex, R5(WIDIndex) - EWRCount)
    }
    val repoOut = Boxes.createRepo(
      ctx,
      EWRId,
      Boxes.getTokenCount(EWRId, repo) + EWRCount,
      Boxes.getTokenCount(Configs.tokens.RSN, repo) - EWRCount * 100L,
      R4.toSeq.map(item => item.toArray),
      R5,
      WIDIndex
    )
    var candidates = Seq(repoOut)
    val boxes = Seq(repo, permit.EWRBox, permit.WIDBox, box1)
    val tokens = Boxes.calcTotalErgAndTokens(Seq(permit.EWRBox, permit.WIDBox, box1))
    tokens.update(Configs.tokens.RepoNFT, 0)
    tokens.update(EWRId, tokens.getOrElse(EWRId, 0L) - permit.EWRBox.getTokens.get(0).getValue)
    if (permit.EWRBox.getTokens.get(0).getValue > EWRCount) {
      val permitOut = Boxes.createPermitBox(ctx, EWRId, Boxes.getTokenCount(EWRId, permit.EWRBox) - EWRCount, permit.WIDBox.getTokens.get(0).getId.getBytes)
      candidates = candidates ++ Seq(permitOut)
    }
    tokens.update(Configs.tokens.RSN, tokens.getOrElse(Configs.tokens.RSN, 0L) + EWRCount * 100)
    val totalErgIn: Long = tokens.getOrElse("", 0L)
    val userOutBuilder = ctx.newTxBuilder().outBoxBuilder()
      .contract(ctx.newContract(prover.getAddress.asP2PK().script))
      .value(totalErgIn - Configs.fee - repo.getValue)
    tokens.keys.foreach(tokenId => {
      val amount: Long = tokens.getOrElse(tokenId, 0)
      if (amount > 0 && tokenId != "") {
        userOutBuilder.tokens(new ErgoToken(tokenId, amount))
      }
    })
    candidates = candidates ++ Seq(userOutBuilder.build())
    val returnPermitTxUnsigned = ctx.newTxBuilder().boxesToSpend(boxes.asJava)
      .fee(Configs.fee)
      .outputs(candidates: _*)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val returnPermitTx = prover.sign(returnPermitTxUnsigned)
    //    println(returnPermitTx.toJson(true))
    println(s"get permit: ${permit.getWIDHex()}")
    repo = returnPermitTx.getOutputsToSpend.get(0)
    permit.EWRBox = returnPermitTx.getOutputsToSpend.get(1)
    //    permit.WIDBox = returnPermitTx.getOutputsToSpend.get(2)
  }

  def redeemRepo(ctx: BlockchainContext): Unit = {
    val prover = getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(Configs.tokens.GuardNFT, 1))
    val inputs = Seq(repo, box1)
    val boxBuilder = ctx.newTxBuilder().outBoxBuilder()
      .contract(ctx.newContract(prover.getAddress.asP2PK().script))
      .registers(
        repo.getRegisters.get(0),
        repo.getRegisters.get(1),
        repo.getRegisters.get(2),
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
    val permit = permits(index);
    val WID = permit.getWID()
    val box1 = Boxes.createBoxForUser(ctx, permit.getProver().getAddress, 1e9.toLong)
    val permitOut = Boxes.createPermitBox(ctx, EWRId, Boxes.getTokenCount(EWRId, permit.EWRBox) - 1, WID)
    val boxes = Seq(permit.EWRBox, permit.WIDBox, box1)
    val totalErg = boxes.map(item => item.getValue).reduce((a, b) => a + b)
    val userChange = Boxes.createBoxCandidateForUser(
      ctx,
      permit.getProver().getAddress,
      totalErg - Configs.fee - 2 * Configs.minBoxValue,
      new ErgoToken(WID, 1)
    )
    val commitmentBox = Boxes.createCommitment(
      ctx,
      EWRId,
      permit.getWID(),
      commitment.requestId(),
      commitment.hash(WID)
    )
    val commitmentUnsigned = ctx.newTxBuilder().boxesToSpend(boxes.asJava)
      .fee(Configs.fee)
      .sendChangeTo(permit.getProver().getAddress.getErgoAddress)
      .outputs(permitOut, commitmentBox, userChange)
      .build()
    val commitmentTx = permit.getProver().sign(commitmentUnsigned)
    permit.commitment = commitmentTx.getOutputsToSpend.get(1)
    permit.EWRBox = commitmentTx.getOutputsToSpend.get(0)
    println(s"user committed: ${permit.getWIDHex()}")
  }

  def redeemCommitment(ctx: BlockchainContext, index: Int): Unit = {
    val permit = permits(index);
    val prover = permit.getProver()
    val box = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong);
    val newPermit = Boxes.createPermitBox(ctx, EWRId, 1, permit.getWID())
    val inputs = Seq(permit.commitment, permit.WIDBox, box)
    val redeemUnsigned = ctx.newTxBuilder().boxesToSpend(inputs.asJava)
      .fee(Configs.fee)
      .outputs(newPermit)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val tx = prover.sign(redeemUnsigned)
    permit.EWRBox = tx.getOutputsToSpend.get(0)
    permit.commitment = null
    println(s"commitment redeem ${permit.getWIDHex()}")
  }

  def triggerEvent(ctx: BlockchainContext, index: Int, exclude: Seq[Int], commitment: Commitment): Unit = {
    val permit = permits(index)
    val processPermits = permits.zipWithIndex.collect {
      case (a, i) if !exclude.contains(i) => a
    }.filter(item => item != null)
    val commitments = processPermits.map(item => item.commitment).filter(item => item != null)
    val WIDs = processPermits.filter(item => item.commitment != null).map(item => item.getWID())
    val trigger = Boxes.createTriggerEventBox(ctx, EWRId, WIDs, commitment)
    val box1 = Boxes.createBoxForUser(ctx, permit.getProver().getAddress, 1e9.toLong)
    val unsignedTx = ctx.newTxBuilder().boxesToSpend((commitments ++ Seq(box1)).asJava)
      .fee(Configs.fee)
      .outputs(trigger)
      .withDataInputs(Seq(repo).asJava)
      .sendChangeTo(permit.getProver().getAddress.getErgoAddress)
      .build()
    val tx = permit.getProver().sign(unsignedTx)
    triggerEvent = tx.getOutputsToSpend.get(0)
    processPermits.foreach(item => item.commitment = null)
    println(s"User Merged commitments ${permit.getWIDHex()}")
  }

  def guardPayment(ctx: BlockchainContext, commitment: Commitment): Unit = {
    val prover = getProver()
    val wRepo = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(commitment.targetChainTokenId, commitment.fee))
    val guardBox = Boxes.createBoxForUser(ctx, prover.getAddress, Configs.minBoxValue, new ErgoToken(Configs.tokens.GuardNFT, 1))
    val WIDs = triggerEvent.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.map(item => item.toArray)
    val WIDHex = WIDs.map(item => Base16.encode(item))
    val notMergedPermits = permits.filter(item => item.commitment != null && !WIDHex.contains(item.getWIDHex()))
    val notMergedWIDs = notMergedPermits.map(item => item.getWID())
    val userFee = commitment.fee / (WIDs.length + notMergedWIDs.length)
    val newPermits = (WIDs ++ notMergedWIDs).map(item => {
      Boxes.createPermitBox(ctx, EWRId, 1, item, new ErgoToken(commitment.targetChainTokenId, userFee))
    })
    val inputs = Seq(triggerEvent, guardBox, wRepo) ++ notMergedPermits.map(item => item.commitment)
    val unsignedTx = ctx.newTxBuilder().boxesToSpend(inputs.asJava)
      .fee(Configs.fee)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .outputs(newPermits: _*)
      .build()
    val tx = prover.sign(unsignedTx)
    notMergedPermits.foreach(item => item.commitment = null)
    println("guard payment completed")
    triggerEvent = null;
  }

  def mergeFraudToRepo(ctx: BlockchainContext, fraud: InputBox): Unit = {
    val prover = getProver()
    val box2 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(Configs.tokens.CleanupNFT, 1L))
    val WID = fraud.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray(0).toArray.clone()
    val EWRCount = repo.getTokens.get(1).getValue.toLong + 1
    val RSNCount = repo.getTokens.get(2).getValue.toLong - 100
    var users = repo.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.map(item => item.toArray).clone()
    var userEWR = repo.getRegisters.get(1).getValue.asInstanceOf[Coll[Long]].toArray.clone()
    val userIndex = users.map(item => Base16.encode(item)).indexOf(Base16.encode(WID), 0)
    if(userIndex < 0){
      println(s"user ${Base16.encode(WID)} not found in repo")
      return
    }
    if(userEWR(userIndex) > 1){
      userEWR(userIndex) -= 1;
    }else {
      userEWR = userEWR.patch(userIndex, Nil, 1)
      users = users.patch(userIndex, Nil, 1)
    }
    val repoCandidate = Boxes.createRepo(ctx, EWRId, EWRCount, RSNCount, users, userEWR, userIndex)
    val unsigned = ctx.newTxBuilder().boxesToSpend(Seq(repo, fraud, box2).asJava)
      .fee(Configs.fee)
      .outputs(repoCandidate)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val signed = prover.sign(unsigned)
    repo = signed.getOutputsToSpend.get(0)
    println(s"one ${Base16.encode(WID)} tokens slashed (user index: $userIndex)")
  }

  def moveToFraud(ctx: BlockchainContext): Unit = {
    val prover = getProver()
    val WIDs = triggerEvent.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray.map(item => item.toArray)
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong, new ErgoToken(Configs.tokens.CleanupNFT, 1L))
    val newFraud = WIDs.indices.map(index => {
      Boxes.createFraudBox(ctx, EWRId, WIDs(index))
    })
    val unsignedTx = ctx.newTxBuilder().boxesToSpend(Seq(triggerEvent, box1).asJava)
      .fee(Configs.fee)
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .outputs(newFraud: _*)
      .build()
    val tx = prover.sign(unsignedTx)
    println(s"Fraud detected. Generated fraud box count = ${tx.getOutputsToSpend.size()}")
    triggerEvent = null;
    newFraud.indices.foreach(index => mergeFraudToRepo(ctx, tx.getOutputsToSpend.get(index)))
  }

  def main(args: Array[String]): Unit = {
    Configs.ergoClient.execute(ctx => {
      createRepoBox(ctx, 1e12.toLong, 100L, "ADA")
      (1 to 7).foreach(item => getPermit(ctx, 10000L))
      returnPermit(ctx, 100L, 2)
      returnPermit(ctx, 99L, 2)
      redeemRepo(ctx)
      val commitment = new Commitment()
      permits.indices.foreach(index => createCommitment(ctx, commitment, index))
      redeemCommitment(ctx, 0)
      triggerEvent(ctx, 1, Seq(2), commitment)
      guardPayment(ctx, commitment)
      permits.indices.foreach(index => createCommitment(ctx, commitment, index))
      triggerEvent(ctx, 0, Seq(), commitment)
      moveToFraud(ctx)
      redeemRepo(ctx)
    })
  }
}
