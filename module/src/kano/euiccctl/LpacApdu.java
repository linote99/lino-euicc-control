package kano.euiccctl;

import android.se.omapi.Channel;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * lpac 的 ApduInterface 实现（Java 侧），把 lpac 的 APDU 调用桥接到 OMAPI 通道。
 *
 * 对应 Kotlin 接口（libs/lpac-jni/.../ApduInterface.kt）：
 *   fun connect()
 *   fun disconnect()
 *   fun logicalChannelOpen(aid: ByteArray): Int
 *   fun logicalChannelClose(handle: Int)
 *   fun transmit(handle: Int, tx: ByteArray): ByteArray
 *   val valid: Boolean
 *
 * 实现方式：用 java.lang.reflect.Proxy 动态实现上述接口，方法按名字分派。
 * 之所以用代理而非直接实现接口：lpac-jni 的 Kotlin 接口不在本 APK 的类路径上
 * （它在 OpenEUICC.apk 里），编译期无法引用，只能运行时按名字匹配。
 *
 * 关键点：OMAPI 的 Channel 一开一关成本高，且 lpac 会频繁 transmit，
 * 因此按 AID 缓存已打开的 Channel，close 时才真正关闭。
 */
class LpacApdu {

    private final Object session;      // android.se.omapi.Session
    private final Map<Integer, Object> channels = new HashMap<Integer, Object>();
    private final Map<Integer, String> chanAid = new HashMap<Integer, String>();
    private int nextHandle = 1;

    LpacApdu(Object omapiSession) {
        this.session = omapiSession;
    }

    /** 创建实现 ApduInterface 的代理对象 */
    Object newProxy(ClassLoader lpacLoader) {
        return Proxy.newProxyInstance(
                lpacLoader,
                new Class<?>[]{ findInterface("net.typeblog.lpac_jni.ApduInterface") },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                        String n = m.getName();
                        if ("connect".equals(n))    { return null; }
                        if ("disconnect".equals(n)) { closeAll(); return null; }
                        if ("logicalChannelOpen".equals(n))
                            return logicalChannelOpen((byte[]) args[0]);
                        if ("logicalChannelClose".equals(n))
                            { logicalChannelClose(((Number) args[0]).intValue()); return null; }
                        if ("transmit".equals(n))
                            return transmit(((Number) args[0]).intValue(), (byte[]) args[1]);
                        if ("valid".equals(n))      return Boolean.TRUE;
                        // Kotlin 接口的默认方法 withLogicalChannel 走这里（若有）
                        if ("toString".equals(n))   return "LpacApduProxy";
                        if ("hashCode".equals(n))   return System.identityHashCode(proxy);
                        if ("equals".equals(n))     return proxy == args[0];
                        throw new UnsupportedOperationException("ApduInterface." + n);
                    }
                });
    }

    /**
     * 定位 lpac 的接口类。
     *
     * 注意必须用 lpacLoader 而非本类自己的 ClassLoader：这些接口在 OpenEUICC.apk 的
     * dex 里，只有 DexClassLoader 看得见。用错会得到 ClassNotFoundException。
     */
    static Class<?> findInterface(String name) {
        return findInterface(name, null);
    }

    static Class<?> findInterface(String name, ClassLoader lpacLoader) {
        if (lpacLoader == null) lpacLoader = LpacClassLoader.get();
        try { return Class.forName(name, true, lpacLoader); }
        catch (Throwable t) { throw new RuntimeException("缺少 " + name + "（lpac 类加载器未就绪）", t); }
    }

    private synchronized int logicalChannelOpen(byte[] aid) {
        try {
            Method open = session.getClass().getMethod("openLogicalChannel", byte[].class);
            Object ch = open.invoke(session, (Object) aid);
            if (ch == null) return -1;
            int h = nextHandle++;
            channels.put(h, ch);
            chanAid.put(h, Tlv.hex(aid));
            return h;
        } catch (Throwable t) {
            throw new RuntimeException("openLogicalChannel 失败: " + t, t);
        }
    }

    private synchronized void logicalChannelClose(int handle) {
        Object ch = channels.remove(handle);
        chanAid.remove(handle);
        if (ch == null) return;
        try { ch.getClass().getMethod("close").invoke(ch); } catch (Throwable ignored) {}
    }

    private synchronized void closeAll() {
        for (Integer h : new java.util.ArrayList<Integer>(channels.keySet())) logicalChannelClose(h);
    }

    /** APDU 透传。OMAPI 的 Channel.transmit 已处理 61xx，直接返回响应 */
    private byte[] transmit(int handle, byte[] tx) {
        Object ch = channels.get(handle);
        if (ch == null) throw new RuntimeException("无效通道 " + handle);
        try {
            Method m = ch.getClass().getMethod("transmit", byte[].class);
            byte[] r = (byte[]) m.invoke(ch, (Object) tx);
            return r == null ? new byte[0] : r;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            throw new RuntimeException("transmit 失败: " + c, c);
        }
    }
}


/** 持有 lpac 的 ClassLoader 供各处共用（由 LpacBridge.prepare 设置） */
final class LpacClassLoader {
    private static volatile ClassLoader LOADER;
    static void set(ClassLoader cl) { LOADER = cl; }
    static ClassLoader get() {
        ClassLoader cl = LOADER;
        if (cl == null) throw new IllegalStateException("lpac ClassLoader 未初始化");
        return cl;
    }
}
