package gjavac.test.java;
import gjavac.lib.UvmMap;

public class Storage {
    public String name; // both field and property supported
    public String symbol;
    public String supply ; //big int str
    public Long precision ;
    public String state ;
    public String allowLock ;
    public UvmMap<String> users ;
    public UvmMap<String> allowed ; // authorizer => {userAddress=>amount int}
    public UvmMap<String> lockedAmounts ; // userAddress => "lockedAmount,unlockBlockNumber"
    public String admin ;


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSupply() {
        return supply;
    }

    public void setSupply(String supply) {
        this.supply = supply;
    }

    public Long getPrecision() {
        return precision;
    }

    public void setPrecision(Long precision) {
        this.precision = precision;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAllowLock() {
        return allowLock;
    }

    public void setAllowLock(String allowLock) {
        this.allowLock = allowLock;
    }

    public UvmMap<String> getUsers() {
        return users;
    }

    public void setUsers(UvmMap<String> users) {
        this.users = users;
    }

    public UvmMap<String> getAllowed() {
        return allowed;
    }

    public void setAllowed(UvmMap<String> allowed) {
        this.allowed = allowed;
    }

    public UvmMap<String> getLockedAmounts() {
        return lockedAmounts;
    }

    public void setLockedAmounts(UvmMap<String> lockedAmounts) {
        this.lockedAmounts = lockedAmounts;
    }

    public String getAdmin() {
        return admin;
    }

    public void setAdmin(String admin) {
        this.admin = admin;
    }
}
