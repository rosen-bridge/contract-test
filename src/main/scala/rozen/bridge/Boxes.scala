package rozen.bridge

import helpers.Configs
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoContract, ErgoToken, ErgoType, ErgoValue, InputBox, JavaHelpers, OutBox, UnsignedTransaction}
import special.collection.Coll

import scala.collection.JavaConverters._
import scala.util.Random

object Boxes {

  private def getRandomHexString(length: Int = 64) = {
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
        ErgoValue.of(JavaHelpers.SigmaDsl.Colls.fromArray(Array(100L, 51L)), ErgoType.longType()),
        ErgoValue.of(R7)
      )
    if(RSNCount > 0){
      bankBuilder.tokens(new ErgoToken(Configs.tokens.RSN, RSNCount))
    }
//    println(bankBuilder.build().convertToInputWith(getRandomHexString(),0).toJson(true))
    bankBuilder.build()
  }

  def createLockBox(ctx: BlockchainContext, EWRId: String, EWRCount: Long, UTP: Array[Byte]): OutBox = {
    val txB = ctx.newTxBuilder()
    txB.outBoxBuilder()
      .contract(Contracts.WatcherLock)
      .tokens(new ErgoToken(EWRId, EWRCount))
      .registers(ErgoValue.of(UTP))
      .build()
  }

  def getTokenCount(TokenId: String, box: InputBox): Long = {
    val EWRToken = box.getTokens.asScala.filter(token => token.getId.toString == TokenId).toArray;
    if(EWRToken.length == 0) 0 else EWRToken(0).getValue
  }

  //  def createEvent(ctx: BlockchainContext,  utp: InputBox, ewr: InputBox, eventHash: Array[Byte]): UnsignedTransaction = {
  //
  //  }
  //
  //  def revealEvent(ctx: BlockchainContext, utp: InputBox, eventData: Array[Coll[Byte]], merge_ewr: InputBox*): UnsignedTransaction = {
  //
  //  }
}
