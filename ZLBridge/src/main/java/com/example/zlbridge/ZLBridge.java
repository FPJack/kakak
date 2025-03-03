package com.example.zlbridge;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class ZLBridge {
    private WeakReference<WebView> weakWebViewReference;
    private boolean localJS;
    private HashMap<String,RegisterJSHandlerInterface> jsCallbackMap;
    private HashMap<String,EvaluateJSResultCallback> jsResultCallbackHashMap;
    private RegisterJSUndefinedHandlerInterface registerJSUndefinedHandlerInterface;
    private long callbackUniqueKey = 0;
    static final String INTERFACE_OBJECT_NAME = "ZLBridge";
    public ZLBridge(final WebView webView){
        this.weakWebViewReference = new WeakReference(webView);
        jsCallbackMap = new HashMap<>();
        jsResultCallbackHashMap = new HashMap<>();
        webView.addJavascriptInterface(new JSInterface(new MessageHandler() {
            @Override
            public void callback(MsgBody message) {
                final String name = message.name;
                final String callID = message.callID;
                final boolean end = message.end;
                final Object body = message.body;
                final String error = message.error;
                final String jsMethodId = message.jsMethodId;
                if (!TextUtils.isEmpty(callID) && callID.length() > 0) {
                    final EvaluateJSResultCallback jsCallback = jsResultCallbackHashMap.get(callID);
                    if (jsCallback != null) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                jsCallback.onReceiveValue(body,error);
                                if (end) jsResultCallbackHashMap.remove(callID);
                            }
                        });
                    }
                    return;
                }
                final RegisterJSHandlerInterface registerJSHandlerInterface = jsCallbackMap.get(name);
                final JSCallback jsCallback = new JSCallback() {
                    @Override
                    public void callback(Object value, boolean end) {
                        HashMap jsMap = new HashMap();
                        jsMap.put("end",end?1:0);
                        jsMap.put("result",value);
                        JSONObject jsonObject = new JSONObject(jsMap);
                        final String js = "window.zlbridge._nativeCallback('"+ jsMethodId +"'," + "'" + jsonObject.toString() +"');";
                        evaluateJavascript(js, null);
                    }
                };
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (registerJSHandlerInterface == null) {
                            registerJSUndefinedHandlerInterface.callback(name,body,jsCallback);
                        }else {
                            registerJSHandlerInterface.callback(body,jsCallback);
                        }
                    }
                });
            }
        }),INTERFACE_OBJECT_NAME);
        webView.getSettings().setJavaScriptEnabled(true);
    }
    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        jsCallbackMap.clear();
        jsResultCallbackHashMap.clear();
        weakWebViewReference.get().removeJavascriptInterface(INTERFACE_OBJECT_NAME);
    }
    public WebView getWebView(){
        return weakWebViewReference.get();
    }
    public void injectLocalJS(){
        if (this.localJS) return;
        this.localJS = true;
        try {
            String js = assetFile2Str(weakWebViewReference.get().getContext(),"ZLBridge.js");
            evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    Log.d("WebViewJavascriptBridge", value);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static String assetFile2Str(Context c, String urlStr){
        InputStream in = null;
        try{
            in = c.getAssets().open(urlStr);
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in));
            String line = null;
            StringBuilder sb = new StringBuilder();
            do {
                line = bufferedReader.readLine();
                if (line != null && !line.matches("^\\s*\\/\\/.*")) { // 去除注释
                    sb.append(line);
                }
            } while (line != null);
            bufferedReader.close();
            in.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if(in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }
    public void registHandler(String name,RegisterJSHandlerInterface registerJSHandlerInterface){
        if (TextUtils.isEmpty(name)||registerJSHandlerInterface == null) return;
        jsCallbackMap.put(name,registerJSHandlerInterface);
    }
    public void removeRegistedHandler(String name) {
        if (name instanceof String) jsCallbackMap.remove(name);
    }
    public void removeAllRegistedHandler(){
        jsCallbackMap.clear();
    }
    public void registUndefinedHandler(RegisterJSUndefinedHandlerInterface registerJSUndefinedHandlerInterface){
        this.registerJSUndefinedHandlerInterface = registerJSUndefinedHandlerInterface;
    }
    public void callHander(String name,EvaluateJSResultCallback completion) {
        callHander(name,null,completion);
    }
    public void hasNativeMethod(String name, final JSMethodExist jsMethodExist) {
        if (jsMethodExist == null) return;
        if (TextUtils.isEmpty(name)) {
            jsMethodExist.callback(false);
            return;
        }
        String js = "window.zlbridge._hasNativeMethod('"+ name +"');";
        evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                boolean exist =  Boolean.valueOf(value);
                jsMethodExist.callback(exist);
            }
        });
    }
    public void callHander(String name, List args, final EvaluateJSResultCallback completion) {
        args = args == null ? new ArrayList() : args;
        HashMap<String,Object> jsMap = new HashMap();
        jsMap.put("result",args);
        String ID = "";
        if (completion != null) {
            synchronized (ZLBridge.this){
                this.callbackUniqueKey += 1;
                ID = String.valueOf(this.callbackUniqueKey);
            }
            jsMap.put("callID",ID);
            jsResultCallbackHashMap.put(ID,completion);
        }
        JSONObject jsonObject = new JSONObject(jsMap);
        String js = "try{" +
                           "window.zlbridge._nativeCall('"+ name +"'," + "'" + jsonObject.toString() +"');" +
                       "}catch(error){" +
                           "window.ZLBridge.postMessage(JSON.stringify({error:error.message,callID:'" + ID + "',end:true}));" +
                       "}";
        final String finalID = ID;
        evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value == null || value.equals("null")) return;
                HashMap map = ZLBridge.converToMapWithString(value);
                Object error = map.get("error");
                if (error == null) return;
                jsResultCallbackHashMap.remove(finalID);
                if (completion != null) completion.onReceiveValue(null,error instanceof String ? (String) error : null);
            }
        });
    }
    private void evaluateJavascript(final String js, final ValueCallback<String> valueCallback) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    weakWebViewReference.get().evaluateJavascript(js,valueCallback);
                }else {
                    weakWebViewReference.get().loadUrl("javascript:" + js);
                    valueCallback.onReceiveValue(null);
                }
            }
        });
    }
    @FunctionalInterface
    public interface JSBridgeError{
        public void error(String error);
    }
    @FunctionalInterface
    public interface JSMethodExist{
        public void callback(boolean exist);
    }
    @FunctionalInterface
    public interface  JSCallback {
        public void callback(Object value,boolean end);
    }
    @FunctionalInterface
    public interface EvaluateJSResultCallback {
        public void onReceiveValue(Object value,String error);
    }
    @FunctionalInterface
    public interface RegisterJSHandlerInterface {
        public void callback(Object body,JSCallback jsCallBack);
    }
    @FunctionalInterface
    public interface RegisterJSUndefinedHandlerInterface {
        public void callback(String name,Object body,JSCallback jsCallBack);
    }
    @FunctionalInterface
    private interface MessageHandler {
        public void callback(MsgBody message);
    }
    private class JSInterface {
        private MessageHandler messageHandler;
        public JSInterface(MessageHandler messageHandler) {
            this.messageHandler = messageHandler;
        }
        @JavascriptInterface
        public void postMessage(String message) {
            messageHandler.callback(MsgBody.initModel(message));
        }
    }
    static private HashMap converToMapWithString(String string) {
        HashMap data = new HashMap();
        if (string == null || string.equals("null")) return data;
        try {
            JSONObject jsonObject = new JSONObject(string);
            Iterator it = jsonObject.keys();
            while (it.hasNext())
            {
                String key = String.valueOf(it.next());
                Object value = jsonObject.get(key);
                data.put(key, value);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return data;
    }

    static private class MsgBody {
        public String name;
        public String callID;
        public String jsMethodId;
        public Object body;
        public boolean end;
        public String error;
        static MsgBody initModel(String message) {
            MsgBody body = new MsgBody();
            HashMap data = ZLBridge.converToMapWithString(message);
            body.name = (String) data.get("name");
            body.body = data.get("body");
            body.callID = (String) data.get("callID");
            body.error = (String) data.get("error");
            body.jsMethodId = (String)data.get("jsMethodId");
            body.end = (boolean) data.get("end");
            return body;
        }
    }
}


