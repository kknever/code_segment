package org.kk.lib.logger;

import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public class MyLog {
    // 是否开启日志
    public static boolean ENABLE_LOG_OUTPUT = true;
    // 是否显示线程信息
    public static boolean SHOW_THREAD_INFO = true;
    private static final String DEFAULT_TAG = "MyLog/";

    public static void i(String msg) {
        if (ENABLE_LOG_OUTPUT) {
            try {
                Log.i(getTag(), msg);
            } catch (Exception ignored) {
            }
        }
    }

    public static void d(String msg) {
        if (ENABLE_LOG_OUTPUT) {
            try {
                Log.d(getTag(), msg);
            } catch (Exception ignored) {
            }
        }
    }

    public static void w(String msg) {
        if (ENABLE_LOG_OUTPUT) {
            try {
                Log.w(getTag(), msg);
            } catch (Exception ignored) {
            }
        }
    }

    public static void e(String msg) {
        if (ENABLE_LOG_OUTPUT) {
            try {
                Log.e(getTag(), msg);
            } catch (Exception ignored) {
            }
        }
    }

    public static void e(Throwable throwable) {
        if (ENABLE_LOG_OUTPUT) {
            try {
                Log.e(getTag(), throwable == null ? "ERROR" : throwable.getMessage(), throwable);
            } catch (Exception ignored) {
            }
        }
    }

    // 构建日志TAG
    private static String getTag() {
        String finalTag;
        try {
            String caller;
            // 获取调用类定位
            StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            boolean checkedLogClass = false;
            int baseLogClassStackIndex = -1;
            // [1]; //当前方法执行堆栈
            // [2]; //上一级的方法堆栈
            // [3]; //上上一级的方法堆栈
            // 依次类推...
            for (int i = 0; i < trace.length; i++) {
                if (!checkedLogClass) {
                    if (trace[i].getClassName().equals(MyLog.class.getName())) {
                        checkedLogClass = true;
                    }
                } else {
                    if (!trace[i].getClassName().equals(MyLog.class.getName())) {
                        baseLogClassStackIndex = i;
                        break;
                    }
                }
            }

            if (baseLogClassStackIndex == -1) {
                caller = getCallerClassName();
            } else {
//                caller = trace[baseLogClassStackIndex].toString();
                if (TextUtils.isEmpty(trace[baseLogClassStackIndex].getFileName())) {
                    caller = getCallerClassName();
                } else {
                    // 简单的类定位，可点击
                    caller = ".(" + trace[baseLogClassStackIndex].getFileName() + ":" + trace[baseLogClassStackIndex].getLineNumber() + ")";
                }
            }
            finalTag = DEFAULT_TAG + caller;
        } catch (Exception e) {
            // 获取当前类名
            finalTag = DEFAULT_TAG + getCallerClassName();
        }
        if (SHOW_THREAD_INFO) {
            return finalTag + String.format(Locale.US, "[%d-MainThread:%s]", // 线程ID和是否主线程
                    Thread.currentThread().getId(), Looper.myLooper() == Looper.getMainLooper());
        } else {
            return finalTag;
        }
    }

    private static String getCallerClassName() {
        try {
            String callerClass = "";
            StackTraceElement[] trace = new Throwable().fillInStackTrace().getStackTrace();
            for (int i = 2; i < trace.length; i++) {
                if (!trace[i].getClassName().equals(MyLog.class.getName())) {
                    callerClass = trace[i].getClassName();
                    callerClass = callerClass.substring(callerClass.lastIndexOf('.') + 1);
                    break;
                }
            }
            return callerClass;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 简易JSON数据格式化输出
     */
    public static String formatDataFromJson(String response) {
        try {
            if (response.startsWith("{")) {
                JSONObject jsonObject = new JSONObject(response);
                return jsonObject.toString(4);
            } else if (response.startsWith("[")) {
                JSONArray jsonArray = new JSONArray(response);
                return jsonArray.toString(4);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return response;
    }
}
