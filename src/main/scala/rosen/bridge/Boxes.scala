package rosen.bridge

import helpers.Configs
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoContract, ErgoToken, ErgoType, ErgoValue, InputBox, JavaHelpers, OutBox, UnsignedTransaction}
import scorex.util.encode.Base16
import special.collection.Coll

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

object Boxes {

  def getRandomHexString(length: Int = 64) = {
    val r = new Random()
    val sb = new StringBuffer
    while ( {
      sb.length < length
    }) sb.append(Integer.toHexString(r.nextInt))
    sb.toString.substring(0, length)
  }

  def createBoxForUser(ctx: BlockchainContext, address: Address, amount: Long, tokens: ErgoToken*): InputBox = {
    createBoxCandidateForUser(ctx, address, amount, tokens: _*).convertToInputWith(getRandomHexString(), 0)
  }

  def createBoxForUser(ctx: BlockchainContext, address: Address, amount: Long): InputBox = {
    createBoxCandidateForUser(ctx, address, amount).convertToInputWith(getRandomHexString(), 0)
  }

  def createBoxCandidateForUser(ctx: BlockchainContext, address: Address, amount: Long, tokens: ErgoToken*): OutBox = {
    val txb = ctx.newTxBuilder();
    txb.outBoxBuilder()
      .value(amount)
      .tokens(tokens: _*)
      .contract(ctx.newContract(address.asP2PK().script))
      .build()
  }

  def createBoxCandidateForUser(ctx: BlockchainContext, address: Address, amount: Long): OutBox = {
    val txb = ctx.newTxBuilder();
    txb.outBoxBuilder()
      .value(amount)
      .contract(ctx.newContract(address.asP2PK().script))
      .build()
  }

  def createBankBox(
                     ctx: BlockchainContext,
                     EWRTokenId: String,
                     EWRCount: Long,
                     RSNCount: Long,
                     users: Seq[Array[Byte]],
                     userEWR: Seq[Long],
                     R7: Int
                   ): OutBox = {
    val txB = ctx.newTxBuilder()
    val R4 = users.map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray
    val bankBuilder = txB.outBoxBuilder()
      .value(Configs.minBoxValue)
      .tokens(
        new ErgoToken(Configs.tokens.BankNft, 1),
        new ErgoToken(EWRTokenId, EWRCount)
      )
      .contract(Contracts.WatcherBank)
      .registers(
        ErgoValue.of(R4, ErgoType.collType(ErgoType.byteType())),
        ErgoValue.of(JavaHelpers.SigmaDsl.Colls.fromArray(userEWR.toArray), ErgoType.longType()),
        ErgoValue.of(JavaHelpers.SigmaDsl.Colls.fromArray(Array(100L, 51L, 0L, 9999L)), ErgoType.longType()),
        ErgoValue.of(R7)
      )
    if(RSNCount > 0){
      bankBuilder.tokens(new ErgoToken(Configs.tokens.RSN, RSNCount))
    }
//    println(bankBuilder.build().convertToInputWith(getRandomHexString(),0).toJson(true))
    bankBuilder.build()
  }

  def createLockBox(ctx: BlockchainContext, EWRId: String, EWRCount: Long, UTP: Array[Byte], tokens: ErgoToken*): OutBox = {
    val txB = ctx.newTxBuilder()
    val tokensSeq = Seq(new ErgoToken(EWRId, EWRCount)) ++ tokens.toSeq
    txB.outBoxBuilder()
      .value(Configs.minBoxValue)
      .contract(Contracts.WatcherLock)
      .tokens(tokensSeq: _*)
      .registers(
        ErgoValue.of(Seq(UTP).map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray, ErgoType.collType(ErgoType.byteType())),
        // this value must exists in case of redeem commitment.
        ErgoValue.of(Seq(Array(0.toByte)).map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray, ErgoType.collType(ErgoType.byteType())),
      )
      .build()
  }

  def createFraudBox(ctx: BlockchainContext, EWRId: String, UTP: Array[Byte]): OutBox = {
    val txB = ctx.newTxBuilder()
    txB.outBoxBuilder()
      .value(Configs.minBoxValue)
      .contract(Contracts.WatcherFraudLock)
      .tokens(new ErgoToken(EWRId, 1))
      .registers(
        ErgoValue.of(Seq(UTP).map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray, ErgoType.collType(ErgoType.byteType())),
      )
      .build()
  }

  def createCommitment(ctx: BlockchainContext, EWRId: String, UTP:Array[Byte], RequestId: Array[Byte], commitment: Array[Byte]): OutBox = {
    ctx.newTxBuilder().outBoxBuilder()
      .value(Configs.minBoxValue)
      .contract(Contracts.WatcherCommitment)
      .tokens(new ErgoToken(EWRId, 1))
      .registers(
        ErgoValue.of(Seq(UTP).map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray, ErgoType.collType(ErgoType.byteType())),
        ErgoValue.of(Seq(RequestId).map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray, ErgoType.collType(ErgoType.byteType())),
        ErgoValue.of(commitment),
        ErgoValue.of(Contracts.getContractScriptHash(Contracts.WatcherLock)),
      ).build()
  }

  def createTriggerEventBox(ctx: BlockchainContext, EWRId: String, UTP: Seq[Array[Byte]], commitment: Commitment): OutBox = {
    val R4 = UTP.sortWith(Base16.encode(_) > Base16.encode(_)).map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item)).toArray
    val R5 = commitment.partsArray().map(item => JavaHelpers.SigmaDsl.Colls.fromArray(item))
    ctx.newTxBuilder().outBoxBuilder()
      .value(Configs.minBoxValue * UTP.length)
      .contract(Contracts.WatcherTriggerEvent)
      .tokens(new ErgoToken(EWRId, UTP.length))
      .registers(
        ErgoValue.of(R4, ErgoType.collType(ErgoType.byteType())),
        ErgoValue.of(R5, ErgoType.collType(ErgoType.byteType())),
        ErgoValue.of(Contracts.getContractScriptHash(Contracts.WatcherLock))
      ).build()
  }

  def getTokenCount(TokenId: String, box: InputBox): Long = {
    val EWRToken = box.getTokens.asScala.filter(token => token.getId.toString == TokenId).toArray;
    if(EWRToken.length == 0) 0 else EWRToken(0).getValue
  }

  def calcTotalErgAndTokens(boxes: Seq[InputBox]): mutable.Map[String, Long] ={
    val tokens: mutable.Map[String, Long] = mutable.Map()
    val totalErg = boxes.map(item => item.getValue).reduce((a, b) => a + b)
    boxes.foreach(box => {
      box.getTokens.forEach(token => {
        val tokenId = token.getId.toString
        tokens.update(tokenId, tokens.getOrElse(tokenId, 0L) + token.getValue)
      })
    });
    tokens.update("", totalErg);
    tokens
  }

}