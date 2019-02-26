package gjavac.test.kotlin


import gjavac.lib.*
import gjavac.lib.UvmCoreLibs.*

// TODO: 用fast_map_get/fast_map_set重构

class Storage {
    var name: String? = ""
    var symbol: String? = ""
    var supply: String = "0" //big int str
    var precision: Long = 0L
    var users: UvmMap<String>? = UvmMap.create()
    var allowed: UvmMap<String>? = UvmMap.create() // authorizer => {userAddress=>amount int}
    var lockedAmounts: UvmMap<String>? = UvmMap.create() // userAddress => "lockedAmount,unlockBlockNumber"
    var state: String? = ""
    var allowLock: String? = "false"
    var admin: String? = ""
}

interface MultiOwnedContractSimpleInterface {
    fun on_deposit_contract_token(arg: String)
    fun getOn_deposit_contract_token(): Any
}

@Component
class Utils {
    fun NOT_INITED(): String {
        return "NOT_INITED"
    }
    fun COMMON(): String {
        return "COMMON"
    }
    fun PAUSED(): String {
        return "PAUSED"
    }
    fun STOPPED(): String {
        return "STOPPED"
    }

    fun checkAdmin(self: UvmContract<*>) {
        val storage = (self.storage ?: return) as Storage
        if(storage.admin != getFromAddress()) { // use from_address(), not caller_address
            error("you are not admin, can't call this function")
        }
    }

    fun checkCallerFrameValid(self: UvmContract<*>) {
        val prev_contract_id =  get_prev_call_frame_contract_address()
        val prev_api_name = get_prev_call_frame_api_name()
        if (prev_contract_id == null || prev_contract_id== "") {
            return
        }/* else if ( prev_api_name == "vote" || prev_api_name == "voteFunc") {
            return
        }*/
        else if ( prev_contract_id == get_current_contract_address()) {
            return
        } else {
            error("this api can't called by invalid contract:"+prev_contract_id)
        }
        return
    }

    // parse a,b,c format string to [a,b,c]
    fun parseArgs(arg: String?, count: Int, errorMsg: String): UvmArray<String> {
        if(arg==null) {
            error(errorMsg)
            return UvmArray.create()
        }
        val stringModule = importModule(UvmStringModule::class.java, "string")
        val parsed = stringModule.split(arg, ",")
        if(parsed==null || parsed.size() != count) {
            error(errorMsg)
            return UvmArray.create()
        }
        return parsed
    }

    fun parseAtLeastArgs(arg: String?, count: Int, errorMsg: String): UvmArray<String> {
        if(arg==null) {
            error(errorMsg)
            return UvmArray.create()
        }
        val stringModule = importModule(UvmStringModule::class.java, "string")
        val parsed = stringModule.split(arg, ",")
        if(parsed==null || parsed.size() < count) {
            error(errorMsg)
            return UvmArray.create()
        }
        return parsed
    }

    fun arrayContains(col: UvmArray<*>?, item: Any?): Boolean {
        if(col == null || item == null) {
            return false
        }
        val colIter = col.ipairs()
        var colKeyValuePair = colIter(col, 0)
        while(colKeyValuePair.first != null) {
            if(colKeyValuePair!=null && colKeyValuePair.second==item) {
                return true
            }
            colKeyValuePair = colIter(col, colKeyValuePair.first)
        }
        return false
    }

    fun getFromAddress(): String {
        // allow contract as token holder
        val fromAddress: String
        val prev_contract_id = get_prev_call_frame_contract_address()
        if(prev_contract_id!=null && is_valid_contract_address(prev_contract_id)) {
            fromAddress = prev_contract_id
        } else {
            fromAddress = caller_address()
        }
        return fromAddress
    }


    fun checkState(self: TokenContract) {
        val storage = self.storage ?: return
        val state = storage.state
        if(state == NOT_INITED()) {
            error("contract token not inited")
        } else if(state == PAUSED()) {
            error("contract paused")
        } else if(state==STOPPED()) {
            error("contract stopped")
        }
    }

    fun checkStateInited(self: TokenContract) {
        val state = self.storage?.state ?: return
        if(state == NOT_INITED()) {
            error("contract token not inited")
        }
    }

    fun checkAddress(addr: String): Boolean {
        val result = is_valid_address(addr)
        if (!result) {
            error("address format error")
            return false
        } else {
            return true
        }
    }

    fun getBalanceOfUser(self: TokenContract, addr: String): String {
        val balance = fast_map_get("users",addr)
        if(balance == null) {
            return "0"
        }else {
            return tostring(balance)
        }
    }

}

@Contract(storage = Storage::class)
class TokenContract : UvmContract<Storage>() {
    override fun init() {
        val storage = this.storage ?: return
        storage.name = ""
        storage.symbol = ""
        storage.supply = "0"
        storage.precision = 1L
        storage.users = UvmMap.create()
        storage.allowed = UvmMap.create()
        storage.lockedAmounts = UvmMap.create()
        val utils = Utils()
        storage.state = utils.NOT_INITED()
        storage.allowLock = "false"
        storage.admin = caller_address()
        pprint("storage: " + tojsonstring(storage))
    }

    @Offline
    fun state(arg: String): String? {
        return this.storage?.state
    }
    @Offline
    fun tokenName(arg: String): String? {
        return this.storage?.name
    }
    @Offline
    fun tokenSymbol(arg: String): String? {
        return this.storage?.symbol
    }
    @Offline
    fun precision(arg: String): Long? {
        return this.storage?.precision
    }
    @Offline
    fun supply(arg: String): String? {
        return this.storage?.supply
    }

    @Offline
    fun admin(arg: String): String? {
        return this.storage?.admin
    }

    @Offline
    fun isAllowLock(arg: String): String? {
        return this.storage?.allowLock
    }

    override fun on_deposit(num: Long) {
        error("not support deposit to token contract")
    }

    // arg: name,symbol,supply,precision
    fun init_token(arg: String) {
        val utils = Utils()
        val json = importModule(UvmJsonModule::class.java, "json")
        utils.checkAdmin(this)
        utils.checkCallerFrameValid(this)
        print("arg: $arg")
        val storage = this.storage ?: return
        if(storage.state != utils.NOT_INITED()) {
            error("this token contract inited before")
            return
        }
        val parsed = utils.parseAtLeastArgs(arg, 4, "argument format error, need format: name,symbol,supply,precision")
        val info = UvmMap.create<Any>()
        val name = parsed[1]
        val symbol = parsed[2]
        val supplystr = parsed[3]
        val precision = tointeger(parsed[4])
        info["name"] = name
        info["symbol"] = symbol
        info["supply"] = supplystr
        info["precision"] = precision
        if(name == null || name.length < 1) {
            error("name needed")
            return
        }
        storage.name = name
        if(symbol==null || symbol.length < 1) {
            error("symbol needed")
            return
        }
        storage.symbol = symbol

        val safemathModule = importModule(UvmSafeMathModule::class.java,"safemath")
        val bigintSupply = safemathModule.bigint(supplystr)
        val bigint0 = safemathModule.bigint(0)
        if (supplystr == null || safemathModule.le(bigintSupply,bigint0)) {
            error("invalid supply:" + supplystr)
        }


        storage.supply = supplystr
        var fromAddress = utils.getFromAddress()
        if(fromAddress==null)fromAddress = "null"
        var callerAddr = caller_address()

        if(fromAddress != callerAddr) {
            error("init_token can't be called from other contract:"+fromAddress)
            return
        }
        fast_map_set("users",callerAddr,supplystr)

        if(precision<1) {
            error("precision must be positive integer")
            return
        }
        val allowedPrecisions = UvmArray.create<Long>()
        allowedPrecisions.add(1L)
        allowedPrecisions.add(10L)
        allowedPrecisions.add(100L)
        allowedPrecisions.add(1000L)
        allowedPrecisions.add(10000L)
        allowedPrecisions.add(100000L)
        allowedPrecisions.add(1000000L)
        allowedPrecisions.add(10000000L)
        allowedPrecisions.add(100000000L)
        if(!utils.arrayContains(allowedPrecisions, precision)) {
            error("precision can only be positive integer in " + json.dumps(allowedPrecisions))
            return
        }
        storage.precision = precision
        storage.state = utils.COMMON()
        emit("Inited", supplystr)

        // TODO: add Transfer event, like newtoken.glua
        val eventArg = UvmMap.create<Any>()
        eventArg["from"] = ""
        eventArg["to"] = callerAddr
        eventArg["amount"] = supplystr
        val eventArgStr = tojsonstring(eventArg)
        emit("Transfer", eventArgStr)
    }

    fun openAllowLock(arg: String) {
        val utils = Utils()
        utils.checkAdmin(this)
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        if(storage.allowLock == "true") {
            error("this contract had been opened allowLock before")
            return
        }
        storage.allowLock = "true"
        emit("AllowedLock", "")
    }

    @Offline
    fun balanceOf(owner: String): String {
        val utils = Utils()
        utils.checkStateInited(this)
        utils.checkAddress(owner)
        val amountStr = utils.getBalanceOfUser(this, owner)
        return amountStr
    }

    // arg: to_address,integer_amount[,memo]
    fun transfer(arg: String) {
        val utils = Utils()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is to_address,integer_amount[,memo]")
        val to = tostring(parsed[1])
        val amountStr = parsed[2]
        utils.checkAddress(to)

        val safemathModule = importModule(UvmSafeMathModule::class.java,"safemath")
        val bigintAmount = safemathModule.bigint(amountStr)
        val bigint0 = safemathModule.bigint(0)
        if (amountStr == null || safemathModule.le(bigintAmount,bigint0)) {
            error("invalid amount:" + amountStr)
        }

        val fromAddress = utils.getFromAddress()
        if (fromAddress === to) {
            error("fromAddress and toAddress is same："+fromAddress)
            return
        }

        var temp = fast_map_get("users", fromAddress)
        if(temp==null)temp = "0"
        var fromBalance = safemathModule.bigint(temp)
        temp = fast_map_get("users", to)
        if(temp==null)temp = "0"
        var toBalance = safemathModule.bigint(temp)

        if(safemathModule.lt(fromBalance,bigintAmount)){
            error("insufficient balance:"+safemathModule.tostring(fromBalance))
        }

        fromBalance = safemathModule.sub(fromBalance, bigintAmount)
        toBalance = safemathModule.add(toBalance, bigintAmount)

        val frombalanceStr = safemathModule.tostring(fromBalance)
        if(frombalanceStr == "0"){
            fast_map_set("users", fromAddress, null)
        }else{
            fast_map_set("users", fromAddress, frombalanceStr)
        }

        fast_map_set("users", to, safemathModule.tostring(toBalance))

        if(is_valid_contract_address(to)) {
            // when to is contract address, maybe it's multi-sig-owned contract, call its' callback api
            val multiOwnedContract = importContractFromAddress(MultiOwnedContractSimpleInterface::class.java, to)
            if(multiOwnedContract != null && multiOwnedContract!!.getOn_deposit_contract_token()!=null) {
                multiOwnedContract.on_deposit_contract_token(amountStr)
            }
        }

        val eventArg = UvmMap.create<Any>()
        eventArg["from"] = fromAddress
        eventArg["to"] = to
        eventArg["amount"] = amountStr
        val eventArgStr = tojsonstring(eventArg)
        emit("Transfer", eventArgStr)
    }

    //
    fun transferFrom(arg: String) {
        val utils = Utils()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val parsed = utils.parseAtLeastArgs(arg, 3, "argument format error, need format is fromAddress,toAddress,amount(with precision)")
        val fromAddress = tostring(parsed[1])
        val toAddress = tostring(parsed[2])
        val amountStr = tostring(parsed[3])
        utils.checkAddress(fromAddress)
        utils.checkAddress(toAddress)
        if (fromAddress === toAddress) {
            error("fromAddress and toAddress is same：$fromAddress")
            return
        }

        val safemathModule = importModule(UvmSafeMathModule::class.java,"safemath")
        val bigintAmount = safemathModule.bigint(amountStr)
        val bigint0 = safemathModule.bigint(0)
        if (amountStr == null || safemathModule.le(bigintAmount,bigint0)) {
            error("invalid amount:" + amountStr)
        }


        var temp = fast_map_get("users", fromAddress)
        if(temp==null)temp = "0"
        var bigintFromBalance = safemathModule.bigint(temp)
        var temp2 = fast_map_get("users", toAddress)
        if(temp2==null)temp2 = "0"
        var bigintToBalance = safemathModule.bigint(temp2)

        if(safemathModule.lt(bigintFromBalance,bigintAmount)){
            error("insufficient balance :"+safemathModule.tostring(bigintFromBalance))
        }

        var allowedDataStr = fast_map_get("allowed",fromAddress)
        if(allowedDataStr==null) {
            error("not enough approved amount to withdraw")
            return
        }

        val jsonModule = importModule(UvmJsonModule::class.java, "json")
        val allowedData = totable(jsonModule.loads(tostring(allowedDataStr))) as UvmMap<String>?
        val contractCaller = utils.getFromAddress()
        if(allowedData == null) {
            error("not enough approved amount to withdraw")
            return
        }
        val approvedAmountStr = allowedData[contractCaller]
        if(approvedAmountStr == null){
            error("no approved amount to withdraw")
        }
        var bigintApprovedAmount = safemathModule.bigint(approvedAmountStr)

        if(bigintApprovedAmount==null || safemathModule.gt(bigintAmount,bigintApprovedAmount)) {
            error("not enough approved amount to withdraw")
            return
        }

        bigintFromBalance = safemathModule.sub(bigintFromBalance, bigintAmount)
        var bigintFromBalanceStr = safemathModule.tostring(bigintFromBalance)
        if(bigintFromBalanceStr == "0")bigintFromBalanceStr = null;

        bigintToBalance = safemathModule.add(bigintToBalance, bigintAmount)
        var bigintToBalanceStr = safemathModule.tostring(bigintToBalance)
        if(bigintToBalanceStr == "0")bigintToBalanceStr = null;


        bigintApprovedAmount = safemathModule.sub(bigintApprovedAmount, bigintAmount)


        fast_map_set("users",fromAddress,bigintFromBalanceStr)
        fast_map_set("users",toAddress,bigintToBalanceStr)


        if(safemathModule.tostring(bigintApprovedAmount) == "0") {
            allowedData[contractCaller] = null;
        }
        else{
            allowedData[contractCaller] = safemathModule.tostring(bigintApprovedAmount)
        }

        allowedDataStr = tojsonstring(allowedData)
        fast_map_set("allowed",fromAddress,allowedDataStr)

        if(is_valid_contract_address(toAddress)) {
            // when to is contract address, maybe it's multi-sig-owned contract, call its' callback api
            val multiOwnedContract = importContractFromAddress(MultiOwnedContractSimpleInterface::class.java, toAddress)
            if(multiOwnedContract != null && multiOwnedContract!!.getOn_deposit_contract_token()!=null) {
                multiOwnedContract.on_deposit_contract_token(amountStr)
            }
        }

        val eventArg = UvmMap.create<String>()
        eventArg["from"] = fromAddress
        eventArg["to"] = toAddress
        eventArg["amount"] = amountStr
        val eventArgStr = tojsonstring(eventArg)
        emit("Transfer", eventArgStr)
    }

    // approve other address permission to spend some balance from caller's account
    // arg format: spenderAddress,amount(with precision)
    fun approve(arg: String) {
        val utils = Utils()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is spenderAddress,amount(with precision)")

        val spender = tostring(parsed[1])
        utils.checkAddress(spender)
        val amountStr = tostring(parsed[2])

        val safemathModule = importModule(UvmSafeMathModule::class.java,"safemath")
        val bigintAmount = safemathModule.bigint(amountStr)
        val bigint0 = safemathModule.bigint(0)
        if (amountStr == null || safemathModule.lt(bigintAmount,bigint0)) {
            error("amount must be non-negative integer")
        }


        val contractCaller = utils.getFromAddress()
        val jsonModule = importModule(UvmJsonModule::class.java, "json")
        var allowedDataTable: UvmMap<String>? = null
        var allowedDataStr = fast_map_get("allowed",contractCaller)
        if(allowedDataStr==null) {
            allowedDataTable = UvmMap.create()
        } else {
            allowedDataTable = totable(jsonModule.loads(tostring(allowedDataStr))) as UvmMap<String>?
            if(allowedDataTable == null) {
                error("allowed storage data error")
                return
            }
        }
        if(safemathModule.eq(bigintAmount,bigint0)){
            allowedDataTable!![spender] = null
        }else{
            allowedDataTable!![spender] = amountStr
        }

        fast_map_set("allowed",contractCaller,tojsonstring(allowedDataTable))

        val eventArg = UvmMap.create<Any>()
        eventArg["from"] = contractCaller
        eventArg["spender"] = spender
        eventArg["amount"] = amountStr
        val eventArgStr = tojsonstring(eventArg)
        emit("Approved", eventArgStr)
    }

    // query approved balance of caller by other address
    // arg format: spenderAddress,authorizerAddress
    @Offline
    fun approvedBalanceFrom(arg: String): String {
        val storage = this.storage?:return ""

        val utils = Utils()
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is spenderAddress,authorizerAddress")
        val spender = tostring(parsed[1])
        val authorizer = tostring(parsed[2])
        utils.checkAddress(spender)
        utils.checkAddress(authorizer)

        val allowedDataStr = fast_map_get("allowed",authorizer)
        if(allowedDataStr == null) {
            return "0"
        }
        val jsonModule = importModule(UvmJsonModule::class.java, "json")
        val allowedDataTable = totable(jsonModule.loads(tostring(allowedDataStr))) as UvmMap<String>?
        if(allowedDataTable==null) {
            return "0"
        }
        val allowedAmount = allowedDataTable[spender]
        if(allowedAmount == null) {
            return "0"
        }
        return allowedAmount
    }

    // query all approved balances approved from some user
    // arg format: fromAddress
    @Offline
    fun allApprovedFromUser(arg: String): String {
        val storage = this.storage ?: return ""

        val authorizer = arg
        val utils = Utils()
        utils.checkAddress(authorizer)
        val allowedDataStr = fast_map_get("allowed","authorizer")
        if(allowedDataStr == null) {
            return "{}"
        }
        return tostring(allowedDataStr)
    }

    fun pause(arg: String) {
        val utils = Utils()
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val state = storage.state
        if (state == utils.STOPPED()) {
            error("this contract stopped now, can't pause")
            return
        }
        if(state == utils.PAUSED()) {
            error("this contract paused now, can't pause")
            return
        }
        utils.checkAdmin(this)
        storage.state = utils.PAUSED()
        emit("Paused", "")
    }

    fun resume(arg: String) {
        val utils = Utils()
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val state = storage.state
        if (state != utils.PAUSED()) {
            error("this contract not paused now, can't resume")
            return
        }
        utils.checkAdmin(this)
        storage.state = utils.COMMON()
        emit("Resumed", "")
    }

    fun stop(arg: String) {
        val utils = Utils()
        utils.checkCallerFrameValid(this)
        val storage = this.storage ?: return
        val state = storage.state
        if(state == utils.STOPPED()) {
            error("this contract stopped now, can't stop")
            return
        }
        if(state == utils.PAUSED()) {
            error("this contract paused now, can't stop")
            return
        }
        utils.checkAdmin(this)
        storage.state = utils.STOPPED()
        emit("Stopped", "")
    }

    // arg: integer_amount,unlockBlockNumber
    fun lock(arg: String) {
        val utils = Utils()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        if(storage.allowLock == "false") {
            error("this token contract not allow lock balance")
            return
        }
        val parsed = utils.parseAtLeastArgs(arg, 2, "arg format error, need format is integer_amount,unlockBlockNumber")
        val toLockAmount = parsed[1]
        val unlockBlockNumber = tointeger(parsed[2])

        val safemathModule = UvmCoreLibs.importModule(UvmSafeMathModule::class.java, "safemath")
        var bigintToLockAmount = safemathModule.bigint(toLockAmount)
        var bigint0 = safemathModule.bigint(0L)
        if(toLockAmount==null|| safemathModule.le(bigintToLockAmount,bigint0)) {
            error("to unlock amount must be positive integer")
            return
        }

        if(/*unlockBlockNumber!! == null || */(unlockBlockNumber < get_header_block_num())) {
            error("to unlock block number can't be earlier than current block number " + tostring(get_header_block_num()))
            return
        }
        val fromAddress = utils.getFromAddress()
        if(fromAddress!= caller_address()) {
            error("only common user account can lock balance") // contract account can't lock token
            return
        }

        var temp = fast_map_get("users", fromAddress)
        if(temp==null){
            error("your balance is 0")
            return
        }
        var bigintFromBalance = safemathModule.bigint(temp)
        if(safemathModule.gt(bigintToLockAmount,bigintFromBalance)){
            error("you have not enough balance to lock")
            return
        }

        val lockedAmount = fast_map_get("lockedAmounts",fromAddress)

        if(lockedAmount == null) {
            fast_map_set("lockedAmounts",fromAddress,(tostring(toLockAmount) + "," + tostring(unlockBlockNumber)))
        } else {
            error("you have locked balance now, before lock again, you need unlock them or use other address to lock")
            return
        }

        bigintFromBalance = safemathModule.sub(bigintFromBalance,bigintToLockAmount)
        var bigintFromBalanceStr = safemathModule.tostring(bigintFromBalance)
        if(bigintFromBalanceStr == "0")bigintFromBalanceStr=null
        fast_map_set("users",fromAddress,bigintFromBalanceStr)

        emit("Locked", tostring(toLockAmount))
    }

    fun unlock(arg: String) {
        val utils = Utils()
        utils.checkCallerFrameValid(this)
        val fromAddress = utils.getFromAddress()
        forceUnlock(fromAddress)
    }

    fun forceUnlock(unlockAdress: String) {
        val utils = Utils()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage ?: return
        if(storage.allowLock == "false") {
            error("this token contract not allow lock balance")
            return
        }
        if(unlockAdress == null || unlockAdress == ""){
            error("unlockAdress should not be empty")
            return
        }

        var lockedStr = fast_map_get("lockedAmounts",unlockAdress)

        if(lockedStr == null) {
            error("you have not locked balance")
            return
        }
        val lockedInfoParsed = utils.parseAtLeastArgs(tostring(lockedStr), 2, "locked amount info format error")
        val lockedAmountStr = lockedInfoParsed[1]
        val canUnlockBlockNumber = tointeger(lockedInfoParsed[2])
        if(get_header_block_num() < canUnlockBlockNumber) { //check unlock number
            error("your locked balance only can be unlock after block #"+tostring(canUnlockBlockNumber))
            return
        }

        fast_map_set("lockedAmounts",unlockAdress,null)

        var temp = fast_map_get("users", unlockAdress)
        if(temp==null){
            temp = "0"
        }
        val safemathModule = UvmCoreLibs.importModule(UvmSafeMathModule::class.java, "safemath")
        var bigintFromBalance = safemathModule.bigint(temp)
        val bigintLockedAmount = safemathModule.bigint(tostring(lockedAmountStr))
        bigintFromBalance = safemathModule.add(bigintFromBalance,bigintLockedAmount)
        fast_map_set("users",unlockAdress,safemathModule.tostring(bigintFromBalance))

        var tempevent = unlockAdress+","+tostring(lockedStr)
        emit("Unlocked", tempevent)
    }

    @Offline
    fun lockedBalanceOf(owner: String): String {
        val storage = this.storage?:return ""
        val lockedAmount = fast_map_get("lockedAmounts",owner)
        if(lockedAmount == null) {
            return "0,0"
        }
        return tostring(lockedAmount)
    }

    fun on_deposit_contract_token(arg:String){
        var precontr = get_prev_call_frame_contract_address()
        if(precontr == null || precontr == ""){
            error("null precontr"+arg)
        }
        fast_map_set("users","test_"+precontr,arg)
    }
}

class TokenContractLoader {

    fun main(): UvmContract<*> {
        // entry point of contract
        UvmCoreLibs.print("hello token")
        val contract = TokenContract()

//        contract.storage = Storage()
//        contract.init()

        return contract
    }
}