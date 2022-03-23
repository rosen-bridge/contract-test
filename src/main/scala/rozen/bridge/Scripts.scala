package rozen.bridge

object Scripts {

  lazy val WatcherBankScript: String =
    s"""{
       |  val lockScriptHash = fromBase64("LOCK_SCRIPT_HASH");
       |  val bankOut = OUTPUTS(0)
       |  val bank = SELF
       |  val bankListSize = bank.R5[Coll[Long]].get.size
       |  val bankOutListSize = bankOut.R5[Coll[Long]].get.size
       |  val bankReplication = allOf(
       |    Coll(
       |      bankOut.propositionBytes == bank.propositionBytes,
       |      bankOut.R6[Coll[Long]].get == bank.R6[Coll[Long]].get,
       |      bankOut.tokens(0)._1 == bank.tokens(0)._1,
       |      bankOut.tokens(1)._1 == bank.tokens(1)._1,
       |      bankOut.tokens(2)._1 == bank.tokens(2)._1,
       |    )
       |  )
       |  if(bank.tokens(1)._2 > bankOut.tokens(1)._2){
       |    // Locking RSN.
       |    // [Bank, UserInputs] => [Bank, LockBox, UserUTP]
       |    val lock = OUTPUTS(1)
       |    val UTP = OUTPUTS(2)
       |    val EWRTokenOut = bank.tokens(1)._2 - bankOut.tokens(1)._2
       |    sigmaProp(
       |      allOf(
       |        Coll(
       |          bankReplication,
       |          bankOut.R4[Coll[Coll[Byte]]].get.size == bankListSize + 1,
       |          bankOut.R4[Coll[Coll[Byte]]].get.slice(0, bankOutListSize - 1) == bank.R4[Coll[Coll[Byte]]].get,
       |          bankOut.R4[Coll[Coll[Byte]]].get(bankOutListSize - 1) == bank.id,
       |          bankOut.R5[Coll[Long]].get.size == bankListSize + 1,
       |          bankOut.R5[Coll[Long]].get.slice(0, bankOutListSize - 1) == bank.R5[Coll[Long]].get,
       |          bankOut.R5[Coll[Long]].get(bankOutListSize - 1) == EWRTokenOut,
       |          EWRTokenOut * bank.R6[Coll[Long]].get(0) == bankOut.tokens(2)._2 - bank.tokens(2)._2,
       |          lock.tokens(0)._2 == EWRTokenOut,
       |          blake2b256(lock.propositionBytes) == lockScriptHash,
       |          lock.R4[Coll[Byte]].get == bank.id,
       |          UTP.tokens(0)._1 == bank.id
       |        )
       |      )
       |    )
       |  }else{
       |    // Unlock RSN
       |    // [Bank, EWR] => [Bank]
       |    val locked = INPUTS(1)
       |    val EWRTokenIn = bankOut.tokens(1)._2 - bank.tokens(1)._2
       |    val UTPIndex = bankOut.R7[Int].get
       |    val lockedSize = bank.R5[Coll[Long]].get.size
       |    val UTPCheckInBank = if(bank.R5[Coll[Long]].get(UTPIndex) > EWRTokenIn) {
       |      allOf(
       |        Coll(
       |          bank.R5[Coll[Long]].get(UTPIndex) == bankOut.R5[Coll[Long]].get(UTPIndex) + EWRTokenIn,
       |          bank.R4[Coll[Coll[Byte]]].get == bankOut.R4[Coll[Coll[Byte]]].get
       |        )
       |      )
       |    }else{
       |      allOf(
       |        Coll(
       |          bank.R4[Coll[Coll[Byte]]].get.slice(0, UTPIndex) == bankOut.R4[Coll[Coll[Byte]]].get.slice(0, UTPIndex),
       |          bank.R4[Coll[Coll[Byte]]].get.slice(UTPIndex + 1, lockedSize) == bankOut.R4[Coll[Coll[Byte]]].get.slice(UTPIndex, lockedSize - 1),
       |          bank.R5[Coll[Long]].get.slice(0, UTPIndex) == bankOut.R5[Coll[Long]].get.slice(0, UTPIndex),
       |          bank.R5[Coll[Long]].get.slice(UTPIndex + 1, lockedSize) == bankOut.R5[Coll[Long]].get.slice(UTPIndex, lockedSize - 1)
       |        )
       |      )
       |    }
       |    sigmaProp(
       |      allOf(
       |        Coll(
       |          bankReplication,
       |          EWRTokenIn * bank.R6[Coll[Long]].get(0) == bank.tokens(2)._2 - bankOut.tokens(2)._2,
       |          bank.R4[Coll[Coll[Byte]]].get(UTPIndex) == locked.R4[Coll[Byte]].get,
       |          UTPCheckInBank
       |        )
       |      )
       |    )
       |  }
       |}""".stripMargin

  lazy val WatcherLockScript: String =
    s"""{
       |  sigmaProp(
       |    allOf(
       |      Coll(
       |        true
       |      )
       |    )
       |  )
       |}""".stripMargin

}
