package gjavac.test.java;

import gjavac.lib.Contract;
import gjavac.lib.Offline;

import gjavac.lib.*;
import static gjavac.lib.UvmCoreLibs.*;


@Contract(storage = Storage.class)
class DemoContract extends UvmContract<Storage> {

    @Override
    public void init() {
        this.getStorage().name = "";
        this.getStorage().symbol = "";
        this.getStorage().supply = ""; // int64   含精度 整数
        this.getStorage().precision = 0L; // int64  精度  10的幂
        this.getStorage().state = "NOT_INITED";
        this.getStorage().admin = caller_address();
        this.getStorage().allowLock = false;
        this.getStorage().lockedAmounts = UvmMap.<String>create(); //users balance
        this.getStorage().allowed = UvmMap.<String>create(); //users balance
        this.getStorage().users = UvmMap.<String>create(); //users balance
    }

    @Offline
    public String state(String arg){
        return this.getStorage().state;
    }
    @Offline
    public String tokenName(String arg){
        return this.getStorage().name;
    }
    @Offline
    public Long precision(String arg){
        return this.getStorage().precision;
    }
    @Offline
    public String admin(String arg){
        return this.getStorage().admin;
    }@Offline
    public Boolean allowLock(String arg){  //返回值类型使用Boolean(返回true/false)  如果返回值类型boolean(返回0/1)
        return this.getStorage().allowLock;
    }@Offline
    public String supply(String arg){
        return this.getStorage().supply;
    }

    @Offline
    public String tokenSymbol(String arg){
        return this.getStorage().symbol;
    }



   ///////////////////////////
   public void init_token( String arg) {
       Utils utils = new Utils();
       UvmJsonModule json = (UvmJsonModule)UvmCoreLibs.importModule(UvmJsonModule.class, "json");
       utils.checkAdmin(this);
       utils.checkCallerFrameValid((UvmContract)this);
       Storage storage = (Storage)this.getStorage();
           if (storage.state!=utils.NOT_INITED()) {
               UvmCoreLibs.error("this token contract inited before");
           } else {
               UvmArray parsed = utils.parseAtLeastArgs(arg, 4, "argument format error, need format: name,symbol,supply,precision");
               UvmMap info = UvmMap.create();
               String name = (String)parsed.get(1);
               String symbol = (String)parsed.get(2);
               String supplystr = (String)parsed.get(3);
               long precision = UvmCoreLibs.tointeger(parsed.get(4));
               info.set("name", name);
               info.set("symbol", symbol);
               info.set("supply", supplystr);
               info.set("precision", precision);
               if (name != null && name.length() >= 1) {
                   storage.setName(name);
                   if (symbol != null && symbol.length() >= 1) {
                       storage.setSymbol(symbol);
                       UvmSafeMathModule safemathModule = (UvmSafeMathModule)UvmCoreLibs.importModule(UvmSafeMathModule.class, "safemath");
                       UvmBigInt bigintSupply = safemathModule.bigint(supplystr);
                       UvmBigInt bigint0 = safemathModule.bigint(0);
                       if (supplystr == null || safemathModule.le(bigintSupply, bigint0)) {
                           UvmCoreLibs.error("invalid supply:" + supplystr);
                       }

                       storage.setSupply(supplystr);
                       String fromAddress = utils.getFromAddress();
                       if (fromAddress == null) {
                           fromAddress = "null";
                       }

                       String callerAddr = UvmCoreLibs.caller_address();
                       if (fromAddress!=callerAddr) {
                           UvmCoreLibs.error("init_token can't be called from other contract:" + fromAddress);
                       } else {
                           UvmCoreLibs.fast_map_set("users", callerAddr, supplystr);
                           if (precision < 1L) {
                               UvmCoreLibs.error("precision must be positive integer");
                           } else {
                               UvmArray allowedPrecisions = UvmArray.create();
                               allowedPrecisions.add(1L);
                               allowedPrecisions.add(10L);
                               allowedPrecisions.add(100L);
                               allowedPrecisions.add(1000L);
                               allowedPrecisions.add(10000L);
                               allowedPrecisions.add(100000L);
                               allowedPrecisions.add(1000000L);
                               allowedPrecisions.add(10000000L);
                               allowedPrecisions.add(100000000L);
                               if (!utils.arrayContains(allowedPrecisions, precision)) {
                                   UvmCoreLibs.error("precision can only be positive integer in " + json.dumps(allowedPrecisions));
                               } else {
                                   storage.setPrecision(precision);
                                   storage.setState(utils.COMMON());
                                   UvmCoreLibs.emit("Inited", supplystr);
                               }
                           }
                       }
                   } else {
                       UvmCoreLibs.error("symbol needed");
                   }
               } else {
                   UvmCoreLibs.error("name needed");
               }
       }
   }

    public void openAllowLock( String arg) {
        Utils utils = new Utils();
        utils.checkAdmin(this);
        utils.checkState(this);
        utils.checkCallerFrameValid((UvmContract)this);
        Storage storage = (Storage)this.getStorage();
            if (storage.getAllowLock()) {
                UvmCoreLibs.error("this contract had been opened allowLock before");
            } else {
                storage.setAllowLock(true);
                UvmCoreLibs.emit("AllowedLock", "");
            }
    }

    @Offline
    public String balanceOf( String owner) {
        Utils utils = new Utils();
        utils.checkStateInited(this);
        utils.checkAddress(owner);
        String amountStr = utils.getBalanceOfUser(this, owner);
        return amountStr;
    }

    public void transfer( String arg) {
        Utils utils = new Utils();
        utils.checkState(this);
        utils.checkCallerFrameValid((UvmContract)this);
        if ((Storage)this.getStorage() != null) {
            UvmArray parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is to_address,integer_amount[,memo]");
            String to = UvmCoreLibs.tostring(parsed.get(1));
            String amountStr = (String)parsed.get(2);
            utils.checkAddress(to);
            UvmSafeMathModule safemathModule = (UvmSafeMathModule)UvmCoreLibs.importModule(UvmSafeMathModule.class, "safemath");
            UvmBigInt bigintAmount = safemathModule.bigint(amountStr);
            UvmBigInt bigint0 = safemathModule.bigint(0);
            if (amountStr == null || safemathModule.le(bigintAmount, bigint0)) {
                UvmCoreLibs.error("invalid amount:" + amountStr);
                return;
            }

            String fromAddress = utils.getFromAddress();
            if(fromAddress == to){
                UvmCoreLibs.error("fromAddress and toAddress is same：" + fromAddress);
                return;
            }
            Object temp = UvmCoreLibs.fast_map_get("users", fromAddress);
            if (temp == null) {
                temp = "0";
            }

            UvmBigInt fromBalance = safemathModule.bigint(temp);
            temp = UvmCoreLibs.fast_map_get("users", to);
            if (temp == null) {
                temp = "0";
            }

            UvmBigInt toBalance = safemathModule.bigint(temp);
            if (safemathModule.lt(fromBalance, bigintAmount)) {
                UvmCoreLibs.error("insufficient balance:" + safemathModule.tostring(fromBalance));
            }

            fromBalance = safemathModule.sub(fromBalance, bigintAmount);
            toBalance = safemathModule.add(toBalance, bigintAmount);
            String frombalanceStr = safemathModule.tostring(fromBalance);
            if (frombalanceStr== "0") {
                UvmCoreLibs.fast_map_set("users", fromAddress, (Object)null);
            } else {
                UvmCoreLibs.fast_map_set("users", fromAddress, frombalanceStr);
            }

            UvmCoreLibs.fast_map_set("users", to, safemathModule.tostring(toBalance));
            if (UvmCoreLibs.is_valid_contract_address(to)) {
                MultiOwnedContractSimpleInterface multiOwnedContract = (MultiOwnedContractSimpleInterface)UvmCoreLibs.importContractFromAddress(MultiOwnedContractSimpleInterface.class, to);
                if (multiOwnedContract != null && multiOwnedContract.getOn_deposit_contract_token() != null) {
                    multiOwnedContract.on_deposit_contract_token(amountStr);
                }
            }

            UvmMap eventArg = UvmMap.create();
            eventArg.set("from", fromAddress);
            eventArg.set("to", to);
            eventArg.set("amount", amountStr);
            String eventArgStr = UvmCoreLibs.tojsonstring(eventArg);
            UvmCoreLibs.emit("Transfer", eventArgStr);
        }
    }

    public void transferFrom( String arg) {
        Utils utils = new Utils();
        utils.checkState(this);
        utils.checkCallerFrameValid((UvmContract)this);
        if ((Storage)this.getStorage() != null) {
            UvmArray parsed = utils.parseAtLeastArgs(arg, 3, "argument format error, need format is fromAddress,toAddress,amount(with precision)");
            String fromAddress = UvmCoreLibs.tostring(parsed.get(1));
            String toAddress = UvmCoreLibs.tostring(parsed.get(2));
            String amountStr = UvmCoreLibs.tostring(parsed.get(3));
            utils.checkAddress(fromAddress);
            utils.checkAddress(toAddress);
            if(fromAddress == toAddress){
                UvmCoreLibs.error("fromAddress and toAddress is same：" + fromAddress);
                return;
            }
            UvmSafeMathModule safemathModule = (UvmSafeMathModule)UvmCoreLibs.importModule(UvmSafeMathModule.class, "safemath");
            UvmBigInt bigintAmount = safemathModule.bigint(amountStr);
            UvmBigInt bigint0 = safemathModule.bigint(0);
            if (amountStr == null || safemathModule.le(bigintAmount, bigint0)) {
                UvmCoreLibs.error("invalid amount:" + amountStr);
            }

            Object temp = UvmCoreLibs.fast_map_get("users", fromAddress);
            if (temp == null) {
                temp = "0";
            }

            UvmBigInt bigintFromBalance = safemathModule.bigint(temp);
            Object temp2 = UvmCoreLibs.fast_map_get("users", toAddress);
            if (temp2 == null) {
                temp2 = "0";
            }

            UvmBigInt bigintToBalance = safemathModule.bigint(temp2);
            if (safemathModule.lt(bigintFromBalance, bigintAmount)) {
                UvmCoreLibs.error("insufficient balance :" + safemathModule.tostring(bigintFromBalance));
            }

            Object allowedDataStr = UvmCoreLibs.fast_map_get("allowed", fromAddress);
            if (allowedDataStr == null) {
                UvmCoreLibs.error("not enough approved amount to withdraw");
            } else {
                UvmJsonModule jsonModule = (UvmJsonModule)UvmCoreLibs.importModule(UvmJsonModule.class, "json");
                UvmMap allowedData = (UvmMap)UvmCoreLibs.totable(jsonModule.loads(UvmCoreLibs.tostring(allowedDataStr)));
                String contractCaller = utils.getFromAddress();
                if (allowedData == null) {
                    UvmCoreLibs.error("not enough approved amount to withdraw");
                } else {
                    String approvedAmountStr = (String)allowedData.get(contractCaller);
                    if (approvedAmountStr == null) {
                        UvmCoreLibs.error("no approved amount to withdraw");
                    }

                    UvmBigInt bigintApprovedAmount = safemathModule.bigint(approvedAmountStr);
                    if (bigintApprovedAmount != null && !safemathModule.gt(bigintAmount, bigintApprovedAmount)) {
                        bigintFromBalance = safemathModule.sub(bigintFromBalance, bigintAmount);
                        String bigintFromBalanceStr = safemathModule.tostring(bigintFromBalance);
                        if (bigintFromBalanceStr=="0") {
                            bigintFromBalance = null;
                        }
                        bigintToBalance = safemathModule.add(bigintToBalance, bigintAmount);
                        String bigintToBalanceStr = safemathModule.tostring(bigintToBalance);
                        if (bigintToBalanceStr=="0") {
                            bigintToBalanceStr = null;
                        }

                        bigintApprovedAmount = safemathModule.sub(bigintApprovedAmount, bigintAmount);
                        UvmCoreLibs.fast_map_set("users", fromAddress, bigintFromBalanceStr);
                        UvmCoreLibs.fast_map_set("users", toAddress, bigintToBalanceStr);
                        if (safemathModule.tostring(bigintApprovedAmount)=="0") {
                            allowedData.set(contractCaller, null);
                        } else {
                            allowedData.set(contractCaller, safemathModule.tostring(bigintApprovedAmount));
                        }

                        allowedDataStr = UvmCoreLibs.tojsonstring(allowedData);
                        UvmCoreLibs.fast_map_set("allowed", fromAddress, allowedDataStr);
                        if (UvmCoreLibs.is_valid_contract_address(toAddress)) {
                            MultiOwnedContractSimpleInterface multiOwnedContract = (MultiOwnedContractSimpleInterface)UvmCoreLibs.importContractFromAddress(MultiOwnedContractSimpleInterface.class, toAddress);
                            if (multiOwnedContract != null && multiOwnedContract.getOn_deposit_contract_token() != null) {
                                multiOwnedContract.on_deposit_contract_token(amountStr);
                            }
                        }

                        UvmMap eventArg = UvmMap.create();
                        eventArg.set("from", fromAddress);
                        eventArg.set("to", toAddress);
                        eventArg.set("amount", amountStr);
                        String eventArgStr = UvmCoreLibs.tojsonstring(eventArg);
                        UvmCoreLibs.emit("Transfer", eventArgStr);
                    } else {
                        UvmCoreLibs.error("not enough approved amount to withdraw");
                    }
                }
            }
        }
    }

    public void approve( String arg) {
        Utils utils = new Utils();
        utils.checkState(this);
        utils.checkCallerFrameValid((UvmContract)this);
        if ((Storage)this.getStorage() != null) {
            UvmArray parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is spenderAddress,amount(with precision)");
            String spender = UvmCoreLibs.tostring(parsed.get(1));
            utils.checkAddress(spender);
            String amountStr = UvmCoreLibs.tostring(parsed.get(2));
            UvmSafeMathModule safemathModule = (UvmSafeMathModule)UvmCoreLibs.importModule(UvmSafeMathModule.class, "safemath");
            UvmBigInt bigintAmount = safemathModule.bigint(amountStr);
            UvmBigInt bigint0 = safemathModule.bigint(0);
            if (amountStr == null || safemathModule.lt(bigintAmount, bigint0)) {
                UvmCoreLibs.error("amount must be non-negative integer");
            }

            String contractCaller = utils.getFromAddress();
            UvmJsonModule jsonModule = (UvmJsonModule)UvmCoreLibs.importModule(UvmJsonModule.class, "json");
            UvmMap allowedDataTable = (UvmMap)null;
            Object allowedDataStr = UvmCoreLibs.fast_map_get("allowed", contractCaller);
            if (allowedDataStr == null) {
                allowedDataTable = UvmMap.create();
            } else {
                allowedDataTable = (UvmMap)UvmCoreLibs.totable(jsonModule.loads(UvmCoreLibs.tostring(allowedDataStr)));
                if (allowedDataTable == null) {
                    UvmCoreLibs.error("allowed storage data error");
                    return;
                }
            }

            if(safemathModule.eq(bigintAmount,bigint0)){
                allowedDataTable.set(spender,null);
            }else{
                allowedDataTable.set(spender, amountStr);
            }

            UvmCoreLibs.fast_map_set("allowed", contractCaller, UvmCoreLibs.tojsonstring(allowedDataTable));
            UvmMap eventArg = UvmMap.create();
            eventArg.set("from", contractCaller);
            eventArg.set("spender", spender);
            eventArg.set("amount", amountStr);
            String eventArgStr = UvmCoreLibs.tojsonstring(eventArg);
            UvmCoreLibs.emit("Approved", eventArgStr);
        }
    }

    @Offline
    public String approvedBalanceFrom( String arg) {
        if ((Storage)this.getStorage() != null) {
            Utils utils = new Utils();
            UvmArray parsed = utils.parseAtLeastArgs(arg, 2, "argument format error, need format is spenderAddress,authorizerAddress");
            String spender = UvmCoreLibs.tostring(parsed.get(1));
            String authorizer = UvmCoreLibs.tostring(parsed.get(2));
            utils.checkAddress(spender);
            utils.checkAddress(authorizer);
            Object allowedDataStr = UvmCoreLibs.fast_map_get("allowed", authorizer);
            if (allowedDataStr == null) {
                return "0";
            } else {
                UvmJsonModule jsonModule = (UvmJsonModule)UvmCoreLibs.importModule(UvmJsonModule.class, "json");
                UvmMap allowedDataTable = (UvmMap)UvmCoreLibs.totable(jsonModule.loads(UvmCoreLibs.tostring(allowedDataStr)));
                if (allowedDataTable == null) {
                    return "0";
                } else {
                    String allowedAmount = (String)allowedDataTable.get(spender);
                    return allowedAmount == null ? "0" : allowedAmount;
                }
            }
        } else {
            return "";
        }
    }

    @Offline
    public String allApprovedFromUser( String arg) {
        if ((Storage)this.getStorage() != null) {
            Utils utils = new Utils();
            utils.checkAddress(arg);
            Object allowedDataStr = UvmCoreLibs.fast_map_get("allowed", "authorizer");
            if (allowedDataStr == null) {
                return "{}";
            } else {
                return UvmCoreLibs.tostring(allowedDataStr);
            }
        } else {
            return "";
        }
    }

    public void pause( String arg) {
        Utils utils = new Utils();
        utils.checkCallerFrameValid((UvmContract)this);
        Storage var10000 = (Storage)this.getStorage();
        if (var10000 != null) {
            Storage storage = var10000;
            String state = storage.getState();
            if (state==utils.STOPPED()) {
                UvmCoreLibs.error("this contract stopped now, can't pause");
            } else if (state==utils.PAUSED()) {
                UvmCoreLibs.error("this contract paused now, can't pause");
            } else {
                utils.checkAdmin(this);
                storage.setState(utils.PAUSED());
                UvmCoreLibs.emit("Paused", "");
            }
        }
    }

    public void resume( String arg) {
        Utils utils = new Utils();
        utils.checkCallerFrameValid((UvmContract)this);
        Storage var10000 = (Storage)this.getStorage();
        if (var10000 != null) {
            Storage storage = var10000;
            String state = storage.getState();
            if (state!=utils.PAUSED()) {
                UvmCoreLibs.error("this contract not paused now, can't resume");
            } else {
                utils.checkAdmin(this);
                storage.setState(utils.COMMON());
                UvmCoreLibs.emit("Resumed", "");
            }
        }
    }

    public void stop( String arg) {
        Utils utils = new Utils();
        utils.checkCallerFrameValid((UvmContract)this);
        Storage var10000 = (Storage)this.getStorage();
        if (var10000 != null) {
            Storage storage = var10000;
            String state = storage.getState();
            if (state==utils.STOPPED()) {
                UvmCoreLibs.error("this contract stopped now, can't stop");
            } else if (state==utils.PAUSED()) {
                UvmCoreLibs.error("this contract paused now, can't stop");
            } else {
                utils.checkAdmin(this);
                storage.setState(utils.STOPPED());
                UvmCoreLibs.emit("Stopped", "");
            }
        }
    }

    public void lock( String arg) {
        Utils utils = new Utils();
        utils.checkState(this);
        utils.checkCallerFrameValid((UvmContract)this);
        Storage var10000 = (Storage)this.getStorage();
        if (var10000 != null) {
            Storage storage = var10000;
            if (!storage.getAllowLock()) {
                UvmCoreLibs.error("this token contract not allow lock balance");
            } else {
                UvmArray parsed = utils.parseAtLeastArgs(arg, 2, "arg format error, need format is integer_amount,unlockBlockNumber");
                String toLockAmount = (String)parsed.get(1);
                long unlockBlockNumber = UvmCoreLibs.tointeger(parsed.get(2));
                UvmSafeMathModule safemathModule = (UvmSafeMathModule)UvmCoreLibs.importModule(UvmSafeMathModule.class, "safemath");
                UvmBigInt bigintToLockAmount = safemathModule.bigint(toLockAmount);
                UvmBigInt bigint0 = safemathModule.bigint(0L);
                if (toLockAmount != null && !safemathModule.le(bigintToLockAmount, bigint0)) {
                    if (unlockBlockNumber < UvmCoreLibs.get_header_block_num()) {
                        UvmCoreLibs.error("to unlock block number can't be earlier than current block number " + UvmCoreLibs.tostring(UvmCoreLibs.get_header_block_num()));
                    } else {
                        String fromAddress = utils.getFromAddress();
                        if (fromAddress!=UvmCoreLibs.caller_address()) {
                            UvmCoreLibs.error("only common user account can lock balance");
                        } else {
                            Object temp = UvmCoreLibs.fast_map_get("users", fromAddress);
                            if (temp == null) {
                                UvmCoreLibs.error("your balance is 0");
                            } else {
                                UvmBigInt bigintFromBalance = safemathModule.bigint(temp);
                                if (safemathModule.gt(bigintToLockAmount, bigintFromBalance)) {
                                    UvmCoreLibs.error("you have not enough balance to lock");
                                } else {
                                    Object lockedAmount = UvmCoreLibs.fast_map_get("lockedAmounts", fromAddress);
                                    if (lockedAmount == null) {
                                        UvmCoreLibs.fast_map_set("lockedAmounts", fromAddress, UvmCoreLibs.tostring(toLockAmount) + "," + UvmCoreLibs.tostring(unlockBlockNumber));
                                        bigintFromBalance = safemathModule.sub(bigintFromBalance, bigintToLockAmount);
                                        UvmCoreLibs.fast_map_set("users", fromAddress, safemathModule.tostring(bigintFromBalance));
                                        UvmCoreLibs.emit("Locked", UvmCoreLibs.tostring(toLockAmount));
                                    } else {
                                        UvmCoreLibs.error("you have locked balance now, before lock again, you need unlock them or use other address to lock");
                                    }
                                }
                            }
                        }
                    }
                } else {
                    UvmCoreLibs.error("to unlock amount must be positive integer");
                }
            }
        }
    }

    public void unlock( String arg) {
        Utils utils = new Utils();
        String fromAddress = utils.getFromAddress();
        forceUnlock(fromAddress);

    }

    public void forceUnlock( String unlockAddress) {
        Utils utils = new Utils();
        utils.checkState(this);
        utils.checkCallerFrameValid((UvmContract)this);

            if (this.getStorage().getAllowLock()==false) {
                UvmCoreLibs.error("this token contract not allow lock balance");
            } else {
                Object lockedStr = UvmCoreLibs.fast_map_get("lockedAmounts", unlockAddress);
                if (lockedStr == null) {
                    UvmCoreLibs.error("you have not locked balance");
                } else {
                    UvmArray lockedInfoParsed = utils.parseAtLeastArgs(UvmCoreLibs.tostring(lockedStr), 2, "locked amount info format error");
                    String lockedAmountStr = UvmCoreLibs.tostring(lockedInfoParsed.get(1));
                    long canUnlockBlockNumber = UvmCoreLibs.tointeger(lockedInfoParsed.get(2));
                    if (UvmCoreLibs.get_header_block_num() < canUnlockBlockNumber) {
                        UvmCoreLibs.error("your locked balance only can be unlock after block #" + UvmCoreLibs.tostring(canUnlockBlockNumber));
                        return;
                    }
                    UvmCoreLibs.fast_map_set("lockedAmounts", unlockAddress, (Object)null);
                    Object temp = UvmCoreLibs.fast_map_get("users", unlockAddress);
                    if (temp == null) {
                        temp = "0";
                    }

                    UvmSafeMathModule safemathModule = (UvmSafeMathModule)UvmCoreLibs.importModule(UvmSafeMathModule.class, "safemath");
                    UvmBigInt bigintFromBalance = safemathModule.bigint(temp);
                    UvmBigInt bigintLockedAmount = safemathModule.bigint(UvmCoreLibs.tostring(lockedAmountStr));
                    bigintFromBalance = safemathModule.add(bigintFromBalance, bigintLockedAmount);
                    UvmCoreLibs.fast_map_set("users", unlockAddress, safemathModule.tostring(bigintFromBalance));
                    String tempevent = unlockAddress + "," + UvmCoreLibs.tostring(lockedStr);
                    UvmCoreLibs.emit("Unlocked", tempevent);
                }
            }
    }

    @Offline
    public String lockedBalanceOf( String owner) {
        if ((Storage)this.getStorage() != null) {
            Object lockedAmount = UvmCoreLibs.fast_map_get("lockedAmounts", owner);
            if (lockedAmount == null) {
                return "0,0";
            } else {
                return UvmCoreLibs.tostring(lockedAmount);
            }
        } else {
            return "";
        }
    }





}