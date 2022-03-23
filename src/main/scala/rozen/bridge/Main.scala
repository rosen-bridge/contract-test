package rozen.bridge

import helpers.{Configs, Utils}
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit._
import scorex.util.encode.Base16
import special.collection.Coll
import sun.security.provider.SecureRandom

import scala.collection.JavaConverters._
import scala.collection.mutable

class Lock {
  var sk = Utils.randBigInt
  var EWRBox: InputBox = null;
  var UTPBox: InputBox = null;

  def getProver(): ErgoProver = {
    Configs.ergoClient.execute(ctx => {
      ctx.newProverBuilder().withDLogSecret(sk.bigInteger).build()
    })
  }
}

object Main {
  val sk = Utils.randBigInt
  var EWRId: String = ""
  var bank: InputBox = null
  var locks: Seq[Lock] = Seq()

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
    println("created")
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
    println("locked")
    bank = lockTx.getOutputsToSpend.get(0)
    lock.EWRBox = lockTx.getOutputsToSpend.get(1)
    lock.UTPBox = lockTx.getOutputsToSpend.get(2)
    locks = locks ++ Seq(lock)
  }

  def unlockRSN(ctx: BlockchainContext, EWRCount: Long, index: Int): Unit = {
    val lock = locks(index)
    val prover = lock.getProver()
    val box1 = Boxes.createBoxForUser(ctx, prover.getAddress, 1e9.toLong)
    var R4 = bank.getRegisters.get(0).getValue.asInstanceOf[Coll[Coll[Byte]]].toArray
    val UTPIndex = R4.map(item => item.toArray).indexOf(lock.UTPBox.getTokens.get(0).getId.getBytes)
    var R5 = bank.getRegisters.get(1).getValue.asInstanceOf[Coll[Long]].toArray
    if (R5(UTPIndex) == EWRCount) {
      R4 = (R4.slice(0, UTPIndex).toSeq ++ R4.slice(UTPIndex + 1, R4.length)).toArray
      R5 = (R5.slice(0, UTPIndex).toSeq ++ R5.slice(UTPIndex + 1, R5.length)).toArray
    } else {
      R5.update(UTPIndex, R5.apply(UTPIndex) - EWRCount)
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
    val boxes = Seq(bank, lock.EWRBox, lock.UTPBox, box1)
    val tokens: mutable.Map[String, Long] = mutable.HashMap();
    Seq(lock.EWRBox, lock.UTPBox, box1).foreach(box => {
      box.getTokens.forEach(token => {
        val tokenId = token.getId.toString
        tokens.update(tokenId, tokens.getOrElse(tokenId, 0L) + token.getValue)
      })
    });
    tokens.update(Configs.tokens.BankNft, 0)
    tokens.update(EWRId, tokens.getOrElse(EWRId, 0L) - EWRCount)
    tokens.update(Configs.tokens.RSN, tokens.getOrElse(Configs.tokens.RSN, 0L) + EWRCount * 100)
    val totalErgIn = boxes.map(item => item.getValue).reduce((a, b) => a + b)
    val userOutBuilder = ctx.newTxBuilder().outBoxBuilder()
      .contract(ctx.newContract(prover.getAddress.asP2PK().script))
      .value(totalErgIn - Configs.fee - bank.getValue)
    tokens.keys.foreach(tokenId => {
      val amount: Long = tokens.getOrElse(tokenId, 0)
      if(amount > 0) {
        userOutBuilder.tokens(new ErgoToken(tokenId, amount))
      }
    })
    val unlockTxUnsigned = ctx.newTxBuilder().boxesToSpend(boxes.asJava)
      .fee(Configs.fee)
      .outputs(bankOut, userOutBuilder.build())
      .sendChangeTo(prover.getAddress.getErgoAddress)
      .build()
    val unlockTx = prover.sign(unlockTxUnsigned)
    println("unlocked")
    bank = unlockTx.getOutputsToSpend.get(0)
  }

  def main(args: Array[String]): Unit = {
    Configs.ergoClient.execute(ctx => {
      createBankBox(ctx, 1e12.toLong, 100L, "ADA")
      lockRSN(ctx, 10000L)
      unlockRSN(ctx, 100L, 0)
      //      unlockRSN(ctx, 50L, 0)
    })
  }
}
