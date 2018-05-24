package gjavac.test.kotlin


import gjavac.lib.*
import gjavac.lib.UvmCoreLibs.*

class Storage2 {
    var name: String = ""
    var symbol: String = ""
    var supply: Long = 0L
    var precision: Long = 0L
    var users: UvmMap<Long> = UvmMap.create()
    var allowed: UvmMap<String> = UvmMap.create() // authorizer => {userAddress=>amount int}
    var lockedAmounts: UvmMap<String> = UvmMap.create() // userAddress => "lockedAmount,unlockBlockNumber"
    var state: String = ""
    var allowLock: Boolean = false
    var admin: String = ""
}

interface MultiOwnedContractSimpleInterface {
    fun on_deposit_contract_token(arg: String)
    fun getOn_deposit_contract_token(): Any
}

@Component
class Utils2 {
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
        val storage = (self.storage ?: return) as Storage2
        if(storage.admin != caller_address()) {
            error("you are not admin, can't call this function")
        }
    }

    fun checkCallerFrameValid(self: UvmContract<*>) {
        val prev_contract_id =  get_prev_call_frame_contract_address()
        val prev_api_name = get_prev_call_frame_api_name()
        if (prev_contract_id == null || prev_contract_id!!.length < 1) {
            return
        } else if (prev_api_name == "vote" || prev_api_name == "voteFunc") {
            return
        } else {
            error("this api can't called by invalid contract")
        }
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

    fun arrayContains(col: UvmArray<*>, item: Any?): Boolean {
        if(item == null) {
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

    fun getBalanceOfUser(self: TokenContract, addr: String): Long {
        val balance = self.storage?.users?.get(addr)
        if(balance == null) {
            return 0
        }else {
            return balance
        }
    }

}

@Contract(storage = Storage2::class)
class TokenContract : UvmContract<Storage2>() {
    override fun init() {
        val storage = this.storage ?: return
        storage.name = ""
        storage.symbol = ""
        storage.supply = 0
        storage.precision = 1L
        storage.users = UvmMap.create()
        storage.allowed = UvmMap.create()
        storage.lockedAmounts = UvmMap.create()
        val utils = Utils2()
        storage.state = utils.NOT_INITED()
        storage.allowLock = false
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
    fun totalSupply(arg: String): Long? {
        return this.storage?.supply
    }

    @Offline
    fun admin(arg: String): String? {
        return this.storage?.admin
    }

    @Offline
    fun isAllowLock(arg: String): String? {
        val allowLock = this.storage?.allowLock
        val resultStr = tostring(allowLock)
        return resultStr
    }

    override fun on_deposit(num: Int) {
        error("not support deposit to token contract")
    }

    // arg: name,symbol,supply,precision
    fun init_token(arg: String) {
        val utils = Utils2()
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
        val supply = tointeger(parsed[3])
        val precision = tointeger(parsed[4])
        info["name"] = name
        info["symbol"] = symbol
        info["supply"] = supply
        info["precision"] = precision
        if(name == null || name.length<1) {
            error("name needed")
            return
        }
        storage.name = name
        if(symbol==null || symbol.length<1) {
            error("symbol needed")
            return
        }
        storage.symbol = symbol
        if(supply == null || supply!! < 1) {
            error("supply needed")
            return
        }
        storage.supply = supply
        val fromAddress = utils.getFromAddress()
        val callerAddr = caller_address()
        if(fromAddress != callerAddr) {
            error("init_token can't be called from other contract")
            return
        }
        storage.users[callerAddr] = supply
        if(precision==null || precision!!<1) {
            error("precision must be positive integer")
            return
        }
        val allowedPrecisions = UvmArray.create<Long>()
        allowedPrecisions.add(1)
        allowedPrecisions.add(10)
        allowedPrecisions.add(100)
        allowedPrecisions.add(1000)
        allowedPrecisions.add(10000)
        allowedPrecisions.add(100000)
        allowedPrecisions.add(1000000)
        allowedPrecisions.add(10000000)
        allowedPrecisions.add(100000000)
        if(!utils.arrayContains(allowedPrecisions, precision)) {
            error("precision can only be positive integer in " + json.dumps(allowedPrecisions))
            return
        }
        storage.precision = precision
        storage.state = utils.COMMON()
        val supplyStr = tostring(supply)
        emit("Inited", supplyStr)
    }

    fun openAllowLock(arg: String) {
        val utils = Utils2()
        utils.checkAdmin(this)
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        if(storage.allowLock) {
            error("this contract had been opened allowLock before")
            return
        }
        storage.allowLock = true
        emit("AllowedLock", "")
    }

    @Offline
    fun balanceOf(owner: String): String {
        val utils = Utils2()
        utils.checkStateInited(this)
        utils.checkAddress(owner)
        val amount = utils.getBalanceOfUser(this, owner)
        val amountStr = tostring(amount)
        return amountStr
    }

    @Offline
    fun users(arg: String): String {
        val utils = Utils2()
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is limit(1-based),offset(0-based)}")
        val limit = tointeger(parsed[1])
        val offset = tointeger(parsed[2])
        if(limit == null || offset==null) {
            error("offset is non-negative integer, limit is positive integer")
            return ""
        }
        if(limit!!<1 || offset!!<0 || (offset+limit)!!<=0) {
            error("offset is non-negative integer, limit is positive integer")
            return ""
        }
        val userAddresses = UvmArray.create<String>()
        val users = this.storage?.users ?: return ""
        val usersIter = users.pairs()
        var usersKeyValuePair = usersIter(users, null)
        val tableModule = importModule(UvmTableModule::class.java, "table")
        while(usersKeyValuePair.first!=null) {
            val key = usersKeyValuePair.first as String
            val value = usersKeyValuePair.second
            tableModule.append(userAddresses, key)
            usersKeyValuePair = usersIter(users, key)
        }
        val result = UvmArray.create<String>()
        if(tableModule.length(userAddresses) > offset!!) {
            val userAddressesLength = tableModule.length(userAddresses)
            var i = offset!!
            while(i<=offset!!+limit!!-1) {
                if (i < userAddressesLength) {
                    tableModule.append(result, userAddresses[(i+1).toInt()])
                }
                i += 1
            }
        }
        val resultStr = tojsonstring(result)
        return resultStr
    }

    // arg: to_address,integer_amount[,memo]
    fun transfer(arg: String) {
        val utils = Utils2()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is to_address,integer_amount[,memo]")
        val to = tostring(parsed[1])
        val amount = tointeger(parsed[2])
        utils.checkAddress(to)
        if(amount==null || amount!! < 1) {
            error("amount format error")
            return
        }
        val users = storage.users
        val fromAddress = utils.getFromAddress()
        val fromAddressBalance = users[fromAddress]
        if (fromAddressBalance==null || fromAddressBalance!! < amount!!) {
            error("you have not enoungh amount to transfer out")
            return
        }
        if(is_valid_contract_address(to)) {
            // when to is contract address, maybe it's multi-sig-owned contract, call its' callback api
            val multiOwnedContract = importContractFromAddress(MultiOwnedContractSimpleInterface::class.java, to)
            val amountStr = tostring(amount)
            if(multiOwnedContract != null && multiOwnedContract!!.getOn_deposit_contract_token()!=null) {
                multiOwnedContract.on_deposit_contract_token(amountStr)
            }
        }
        users[fromAddress] = fromAddressBalance!! - amount!!
        val fromAddressBalance2 = users[fromAddress]!!
        if(fromAddressBalance2==0L) {
            users[fromAddress] = null
        }
        val toBalance = users[to]
        if(toBalance!=null) {
            users[to] = toBalance + amount
        } else {
            users[to] = amount
        }
        storage.users = users
        val eventArg = UvmMap.create<Any>()
        eventArg["from"] = fromAddress
        eventArg["to"] = to
        eventArg["amount"] = amount
        val eventArgStr = tojsonstring(eventArg)
        emit("Transfer", eventArgStr)
    }

    //
    fun transferFrom(arg: String) {
        val utils = Utils2()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val parsed = utils.parseAtLeastArgs(arg, 3, "argument format error, need format is fromAddress,toAddress,amount(with precision)")
        val fromAddress = tostring(parsed[1])
        val toAddress = tostring(parsed[2])
        val amount = tointeger(parsed[3])
        utils.checkAddress(fromAddress)
        utils.checkAddress(toAddress)
        if(amount==null || amount!! <= 0) {
            error("amount must be positive integer")
            return
        }
        val allowed = storage.allowed
        val users = storage.users
        val fromAddressBalance = users[fromAddress]
        if(fromAddressBalance==null || amount!! > fromAddressBalance!!) {
            error("fromAddress not have enough token to withdraw")
            return
        }
        val allowedDataStr = allowed[fromAddress]
        if(allowedDataStr==null) {
            error("not enough approved amount to withdraw")
            return
        }
        val jsonModule = importModule(UvmJsonModule::class.java, "json")
        val allowedData = totable(jsonModule.loads(allowedDataStr)) as UvmMap<Long>?
        val contractCaller = utils.getFromAddress()
        if(allowedData == null) {
            error("not enough approved amount to withdraw")
            return
        }
        val approvedAmount = tointeger(allowedData[contractCaller])
        if(approvedAmount==null || amount!! > approvedAmount!!) {
            error("not enough approved amount to withdraw")
            return
        }
        val toAddressBalance = users[toAddress]
        if(toAddressBalance==null) {
            users[toAddress] = amount
        } else {
            users[toAddress] = toAddressBalance!! + amount!!
        }
        users[fromAddress] = fromAddressBalance!! - amount!!
        if(users[fromAddress]!! == 0L) {
            users[fromAddress] = null
        }
        allowedData[contractCaller] = approvedAmount - amount
        if(allowedData[contractCaller] == 0L) {
            allowedData[contractCaller] = null
        }
        allowed[fromAddress] = tojsonstring(allowedData)
        storage.users = users
        storage.allowed = allowed

        val eventArg = UvmMap.create<Any>()
        eventArg["from"] = fromAddress
        eventArg["to"] = toAddress
        eventArg["amount"] = amount
        val eventArgStr = tojsonstring(eventArg)
        emit("Transfer", eventArgStr)
    }

    // approve other address permission to spend some balance from caller's account
    // arg format: spenderAddress,amount(with precision)
    fun approve(arg: String) {
        val utils = Utils2()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is spenderAddress,amount(with precision)")
        val allowed = storage.allowed
        val spender = tostring(parsed[1])
        utils.checkAddress(spender)
        val amount = tointeger(parsed[2])
        if(amount==null || amount!! < 0) {
            error("amount must be non-negative integer")
            return
        }
        val contractCaller = utils.getFromAddress()
        val jsonModule = importModule(UvmJsonModule::class.java, "json")
        var allowedData: UvmMap<Long>? = null
        if(allowed[contractCaller]==null) {
            allowedData = UvmMap.create()
        } else {
            allowedData = totable(jsonModule.loads(allowed[contractCaller])) as UvmMap<Long>?
            if(allowedData == null) {
                error("allowed storage data error")
                return
            }
        }
        allowedData!![spender] = amount
        allowed[contractCaller] = tojsonstring(allowedData)
        storage.allowed = allowed

        val eventArg = UvmMap.create<Any>()
        eventArg["from"] = contractCaller
        eventArg["spender"] = spender
        eventArg["amount"] = amount
        val eventArgStr = tojsonstring(eventArg)
        emit("Approved", eventArgStr)
    }

    // query approved balance of caller by other address
    // arg format: spenderAddress,authorizerAddress
    @Offline
    fun approvedBalanceFrom(arg: String): String {
        val storage = this.storage?:return ""
        val allowed = storage.allowed
        val utils = Utils2()
        val parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is spenderAddress,authorizerAddress")
        val spender = tostring(parsed[1])
        val authorizer = tostring(parsed[2])
        utils.checkAddress(spender)
        utils.checkAddress(authorizer)
        val allowedDataStr = allowed[authorizer]
        if(allowedDataStr == null) {
            return "0"
        }
        val jsonModule = importModule(UvmJsonModule::class.java, "json")
        val allowedData = totable(jsonModule.loads(allowedDataStr)) as UvmMap<Long>?
        if(allowedData==null) {
            return "0"
        }
        val allowedAmount = allowedData[spender]
        if(allowedAmount == null) {
            return "0"
        }
        val allowedAmountStr = tostring(allowedAmount)
        return allowedAmountStr
    }

    // query all approved balances approved from some user
    // arg format: fromAddress
    @Offline
    fun allApprovedFromUser(arg: String): String {
        val storage = this.storage ?: return ""
        val allowed = storage.allowed
        val authorizer = arg
        val utils = Utils2()
        utils.checkAddress(authorizer)
        val allowedDataStr = allowed[authorizer]
        if(allowedDataStr == null) {
            return "{}"
        }
        return allowedDataStr
    }

    fun pause(arg: String) {
        val utils = Utils2()
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
        val utils = Utils2()
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
        val utils = Utils2()
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
        val utils = Utils2()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        if(!storage.allowLock) {
            error("this token contract not allow lock balance")
            return
        }
        val parsed = utils.parseAtLeastArgs(arg, 2, "arg format error, need format is integer_amount,unlockBlockNumber")
        val toLockAmount = tointeger(parsed[1])
        val unlockBlockNumber = tointeger(parsed[2])
        if(toLockAmount==null || toLockAmount!! < 1) {
            error("to unlock amount must be positive integer")
            return
        }
        if(unlockBlockNumber == null || (unlockBlockNumber!! < get_header_block_num())) {
            error("to unlock block number can't be earlier than current block number " + tostring(get_header_block_num()))
            return
        }
        val fromAddress = utils.getFromAddress()
        if(fromAddress!= caller_address()) {
            error("only common user account can lock balance") // contract account can't lock token
            return
        }
        val balance = utils.getBalanceOfUser(this, fromAddress)
        if(toLockAmount!! > balance!!) {
            error("you have not enough balance to lock")
            return
        }
        val lockedAmounts = storage.lockedAmounts
        if(lockedAmounts[fromAddress] == null) {
            lockedAmounts[fromAddress] = tostring(toLockAmount) + "," + tostring(unlockBlockNumber)
        } else {
            error("you have locked balance now, before lock again, you need unlock them or use other address to lock")
            return
        }
        storage.lockedAmounts = lockedAmounts
        storage.users[fromAddress] = balance!! - toLockAmount!!
        emit("Locked", tostring(toLockAmount))
    }

    fun unlock(arg: String) {
        val utils = Utils2()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage ?: return
        if(!storage.allowLock) {
            error("this token contract not allow lock balance")
            return
        }
        val fromAddress = utils.getFromAddress()
        val lockedAmounts = storage.lockedAmounts
        if(lockedAmounts[fromAddress] == null) {
            error("you have not locked balance")
            return
        }
        val lockedInfoParsed = utils.parseAtLeastArgs(lockedAmounts[fromAddress], 2, "locked amount info format error")
        val lockedAmount = tointeger(lockedInfoParsed[1])!!
        val canUnlockBlockNumber = tointeger(lockedInfoParsed[2])!!
        if(get_header_block_num() < canUnlockBlockNumber) {
            error("your locked balance only can be unlock after block #" + tostring(canUnlockBlockNumber))
            return
        }
        lockedAmounts[fromAddress] = null
        storage.lockedAmounts = lockedAmounts
        storage.users[fromAddress] = utils.getBalanceOfUser(this, fromAddress) + lockedAmount
        emit("Unlocked", fromAddress + "," + tostring(lockedAmount))
    }

    fun forceUnlock(arg: String) {
        val utils = Utils2()
        utils.checkState(this)
        utils.checkCallerFrameValid(this)
        val storage = this.storage?:return
        if(!storage.allowLock) {
            error("this token contract not allow lock balance")
            return
        }
        utils.checkAdmin(this)
        val userAddr = arg
        utils.checkAddress(userAddr)
        val lockedAmounts = storage.lockedAmounts
        if(lockedAmounts[userAddr] == null) {
            error("this user have not locked balance")
            return
        }
        val lockedInfoParsed = utils.parseAtLeastArgs(lockedAmounts[userAddr], 2, "locked amount info format error")
        val lockedAmount = tointeger(lockedInfoParsed[1])!!
        val canUnlockBlockNumber = tointeger(lockedInfoParsed[2])!!
        if(get_header_block_num() < canUnlockBlockNumber) {
            error("this user locked balance only can be unlock after block #" + tostring(canUnlockBlockNumber))
        }
        lockedAmounts[userAddr] = null
        storage.lockedAmounts = lockedAmounts
        storage.users[userAddr] = utils.getBalanceOfUser(this, userAddr) + lockedAmount
        emit("Unlocked", userAddr + "," + lockedAmount)
    }

    @Offline
    fun lockedBalanceOf(owner: String): String {
        val storage = this.storage?:return ""
        val lockedAmounts = storage.lockedAmounts
        if(lockedAmounts[owner] == null) {
            return "0,0"
        }
        val resultStr = lockedAmounts[owner]
        return resultStr
    }
}

class TokenContractLoader {

    fun main(): UvmContract<*> {
        // entry point of contract
        UvmCoreLibs.print("hello token")
        val contract = TokenContract()

//        contract.storage = Storage2()
//        contract.init()

        return contract
    }
}