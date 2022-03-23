package rozen.bridge

import helpers.{Configs, Utils}
import org.ergoplatform.appkit.{ConstantsBuilder, ErgoContract}
import scorex.crypto.hash.Digest32
import scorex.util.encode.{Base16, Base64}

object Contracts {
  lazy val WatcherBank: ErgoContract = generateWatcherBankContract()
  lazy val WatcherLock: ErgoContract = generateWatcherLockContract()

  private def getContractScriptHash(contract: ErgoContract): Digest32 = {
    scorex.crypto.hash.Blake2b256(contract.getErgoTree.bytes)
  }


  private def generateWatcherBankContract(): ErgoContract = {
    Configs.ergoClient.execute(ctx => {
      val watcherLockHash = Base64.encode(getContractScriptHash(WatcherLock))
      val sponsorScript = Scripts.WatcherBankScript
        .replace("RSN_TOKEN", Base64.encode(Base16.decode(Configs.tokens.RSN).get))
        .replace("LOCK_SCRIPT_HASH", watcherLockHash)
      val contract = ctx.compileContract(ConstantsBuilder.create().build(), sponsorScript)
      val address = Utils.getContractAddress(contract)
      println(s"Watcher bank address is : \t\t\t$address")
      contract
    })
  }

  private def generateWatcherLockContract(): ErgoContract = {
    Configs.ergoClient.execute(ctx => {
      val sponsorScript = Scripts.WatcherLockScript

      val contract = ctx.compileContract(ConstantsBuilder.create().build(), sponsorScript)
      val address = Utils.getContractAddress(contract)
      println(s"Watcher lock address is : \t\t\t$address")
      contract
    })
  }
}
