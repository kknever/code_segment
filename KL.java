package org.mykk.kkloglib;

import android.os.Looper;
import android.util.Log;

import java.util.Locale;

public class KL {
    private static final boolean ENABLE_LOG_OUTPUT = true;
    private static final boolean ENABLE_TRACE_INFO = true;
    private static final boolean ENABLE_CHECK_MAIN_THREAD = true;
    private static final boolean WRITE_ERROR_LOG_TO_FILE = true;
    private static final String DEFAULT_TAG = "KKLog";

    public static void d(String msg) {
        if (ENABLE_LOG_OUTPUT) {
//            Log.d(getTag(DEFAULT_TAG), buildMessage(msg));
            Log.d(DEFAULT_TAG, buildMessage(msg));
        }
    }

    public static void d(String TAG, String msg) {
        if (ENABLE_LOG_OUTPUT) {
            Log.d(DEFAULT_TAG + "/" + TAG, buildMessage(msg));
        }
    }

    private static void i(String TAG, String msg) {

    }

    // 构建日志TAG
    private static String getTag(String logTag) {
        StackTraceElement[] trace = new Throwable().fillInStackTrace().getStackTrace();
        String callingClass = "";
        for (int i = 2; i < trace.length; i++) {
            Class<?> clazz = trace[i].getClass();
            if (!clazz.equals(KL.class)) {
                callingClass = trace[i].getClassName();
                callingClass = callingClass.substring(callingClass.lastIndexOf('.') + 1);
                break;
            }
        }
        return logTag + "/" + callingClass;
    }

    // 构建日志消息体
    private static String buildMessage(String msg) {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        // [1]; //当前方法执行堆栈
        // [2]; //上一级的方法堆栈
        // [3]; //上上一级的方法堆栈
        // 依次类推...
        boolean checkedLogClass = false;
        int baseLogClassStackIndex = -1;
        for (int i = 0; i < trace.length; i++) {
            if (!checkedLogClass) {
                if (trace[i].getClassName().equals(KL.class.getName())) {
                    checkedLogClass = true;
                }
            } else {
                if (!trace[i].getClassName().equals(KL.class.getName())) {
                    baseLogClassStackIndex = i;
                    break;
                }
            }
        }
        String caller = "";
        if (baseLogClassStackIndex == -1) {
            for (int i = 2; i < trace.length; i++) {
                Class<?> clazz = trace[i].getClass();
                if (!clazz.equals(KL.class)) {
                    caller = trace[i].getMethodName();
                    break;
                }
            }
        } else {
//            caller = trace[baseLogClassStackIndex].getMethodName();
            caller = trace[baseLogClassStackIndex].toString();
        }

        if (ENABLE_CHECK_MAIN_THREAD) {
            return String.format(Locale.US, "[ThreadId: %d, isMainThread: %s] %s: %s",
                    Thread.currentThread().getId(),
                    Looper.myLooper() == Looper.getMainLooper(),
                    ENABLE_TRACE_INFO ? caller : "",
                    msg);
        } else {
            return String.format(Locale.US,"%s: %s",
                    ENABLE_TRACE_INFO ? caller : "", msg);
        }
    }
}
