package gjavac.lib;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class UvmTimeModule {

    private static Date unixTimeStampToDateTime(long unixTimeStamp) {
        Date dtDateTime = new Date(0);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(dtDateTime);
        calendar.add(Calendar.SECOND, (int) unixTimeStamp);
        dtDateTime = calendar.getTime();
        return dtDateTime;
    }

    private static long dateTimeToUnixTimeStamp(Date time) {
        return time.getTime();
    }

    public long add(long timestamp, String field, long offset) {
        Date date = unixTimeStampToDateTime(timestamp);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int intOffset = (int) offset;
        if ("year".equals(field)) {
            calendar.add(Calendar.YEAR, intOffset);
        } else if ("month".equals(field)) {
            calendar.add(Calendar.MONTH, intOffset);
        } else if ("day".equals(field)) {
            calendar.add(Calendar.DATE, intOffset);
        } else if ("hour".equals(field)) {
            calendar.add(Calendar.HOUR, intOffset);
        } else if ("minute".equals(field)) {
            calendar.add(Calendar.MINUTE, intOffset);
        } else if ("second".equals(field)) {
            calendar.add(Calendar.SECOND, intOffset);
        } else {
            throw new RuntimeException("not support time field " + field);
        }

        date = calendar.getTime();
        return dateTimeToUnixTimeStamp(date);
    }

    public String tostr(long timestamp) {
        Date date = unixTimeStampToDateTime(timestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm::ss");
        return sdf.format(date);
    }

    public long difftime(long timestamp1, long timestamp2) {
        return timestamp1 - timestamp2;
    }
}
