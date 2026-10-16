package kano.euiccctl;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 复用 OpenEUICC 的 liblpac-jni.so 实现**无 UI** 的 eSIM 配置下载。
 *
 * 缘起：OpenEUICC 的 DownloadWizardActivity 存在上游竞态缺陷 ——
 *   EuiccChannelManagerService 的连接回调晚于 Activity 的 onSaveInstanceState，
 *   showFragment() 抛 IllegalStateException，改为从外部唤起（多种 flags、
 *   预热服务、唤醒屏幕）均无法稳定规避（实测成功率 0/5 ~ 3/4，波动大）。
 *   故改为直接驱动 lpac，绕开该 Activity。
 *
 * 可行依据（已实证）：
 *   - liblpac-jni.so 自包含：NEEDED 仅 libc/libm/libdl，TLS 与 HTTP 栈静态链接
 *   - 导出 141 个 JNI 符号，含 createContext / euiccInit / downloadProfile
 *   - lpac 以依赖注入方式设计：ApduInterface 与 HttpInterface 由调用方提供，
 *     C 库只负责 SGP.22 的 ES9+ / ES10b 协议逻辑
 *   因此本类提供 OMAPI 版 ApduInterface 与 HttpsURLConnection 版 HttpInterface，
 *   即可完整复用 lpac 的下载实现。
 *
 * 类加载策略：
 *   直接 Class.forName 取不到 lpac 类（不在本 APK 的 dex 里），故用
 *   DexClassLoader 从 OpenEUICC.apk 加载其 dex，并用同一 ClassLoader
 *   加载 liblpac-jni.so（原生库按“库名+类加载器”绑定，必须一致）。
 */
class LpacBridge {

    private static final String OE_APK = "/system/priv-app/OpenEUICC/OpenEUICC.apk";
    private static final String JNI_CLASS = "net.typeblog.lpac_jni.LpacJni";
    private static final String LPA_IFACE = "net.typeblog.lpac_jni.LocalProfileAssistant";
    private static final String INPUT_CLASS = "net.typeblog.lpac_jni.ProfileDownloadInput";

    private final Context ctx;
    private static ClassLoader lpacLoader;
    private static Class<?> jniClass;
    private static Object jniInstance;       // LpacJni 单例（Kotlin object）
    private static Class<?> inputClass;      // 可选（较新上游版本才有）
    private static Class<?> callbackClass;
    private static Class<?> respClass;

    LpacBridge(Context ctx) { this.ctx = ctx; }

    /**
     * 加载 lpac 类；返回 null 表示环境不具备（缺 APK/缺 so 等），调用方降级。
     *
     * ★ 必须是**进程内单例**：liblpac-jni.so 一旦被某个 ClassLoader 打开，
     *   就不能再被另一个 ClassLoader 加载（实测报
     *   UnsatisfiedLinkError: "already opened by ClassLoader ..."）。
     *   因此 loader 与所有反射句柄都缓存在静态字段上，二次调用直接复用。
     */
    String prepare() {
        if (sPrepared) return sPrepareError;
        synchronized (LpacBridge.class) {
            if (sPrepared) return sPrepareError;
            sPrepareError = doPrepare();
            sPrepared = true;
            return sPrepareError;
        }
    }

    private static volatile boolean sPrepared = false;
    private static volatile String sPrepareError = null;

    private String doPrepare() {
        try {
            File apk = new File(OE_APK);
            if (!apk.exists()) return "OpenEUICC.apk 不存在: " + OE_APK;
            // 用 OpenEUICC 自己的 nativeLibraryDir，保证 so 与 ClassLoader 匹配
            String nativeDir = "/system/priv-app/OpenEUICC/lib/arm64";
            File nd = new File(nativeDir);
            if (!nd.exists()) nativeDir = ctx.getApplicationInfo().nativeLibraryDir;

            lpacLoader = new dalvik.system.DexClassLoader(
                    OE_APK, ctx.getCodeCacheDir().getAbsolutePath(), nativeDir,
                    ClassLoader.getSystemClassLoader());
            LpacClassLoader.set(lpacLoader);   // 供 LpacApdu/LpacHttp 定位 lpac 接口

            jniClass     = Class.forName(JNI_CLASS, true, lpacLoader);
            // LpacJni 是 Kotlin `object`（单例）：其 external 函数编译为**实例方法**，
            // 而非 static。反射调用必须传单例作为接收者，否则 invoke(null,..) 会得到
            // "NullPointerException: null receiver"（Kotlin 风格的 NPE 文案）。
            try {
                Field f = jniClass.getDeclaredField("INSTANCE");
                f.setAccessible(true);
                jniInstance = f.get(null);
            } catch (Throwable t) { jniInstance = null; }
            callbackClass= Class.forName("net.typeblog.lpac_jni.ProfileDownloadCallback", true, lpacLoader);
            respClass    = Class.forName("net.typeblog.lpac_jni.HttpInterface$HttpResponse", true, lpacLoader);
            // ProfileDownloadInput 仅存在于较新的上游版本（供 LocalProfileAssistant 接口使用）。
            // 本机 APK 为较早构建，无此类；我们直连 LpacJni.downloadProfile（平铺参数），
            // 故此处按可选加载，缺失不影响功能。
            try {
                inputClass = Class.forName(INPUT_CLASS, false, lpacLoader);
            } catch (Throwable ignored) { inputClass = null; }
            return null;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return "加载 lpac 失败: " + c;
        }
    }

    /**
     * 以正确接收者调用 LpacJni 的方法。
     * static 方法传 null，实例方法（Kotlin object 的成员）传单例。
     */
    private Object callJni(String name, Class<?>[] sig, Object[] args) throws Exception {
        Method m = jniClass.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        Object recv = java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : jniInstance;
        try {
            return m.invoke(recv, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new Exception(name + " 失败: " + c, c);
        }
    }

    /**
     * 自检：只验证桥接链路，不涉及 SM-DP+ 网络。
     *
     * 依次执行 createContext → euiccInit → es10cGetEid / es10cGetProfilesInfo。
     * 若 EID 与 OMAPI 直读一致，则证明：
     *   - DexClassLoader 能加载 OpenEUICC 的 dex 与其绑定的 liblpac-jni.so
     *   - 反射签名（类名/方法名/参数类型）全部正确
     *   - java.lang.reflect.Proxy 实现的 ApduInterface 能被 JNI 正常回调
     * 剩余未验证的仅是与 SM-DP+ 的 HTTPS 交互（需真实激活码）。
     */
    String selfTest(List<String> log, int slotIndex) throws Exception {
        String err = prepare();
        if (err != null) return "ERROR=" + err;
        log.add("CLASSLOADER=ok");

        EuiccIo io = new EuiccIo(ctx, slotIndex);
        io.open();
        try {
            log.add("READER=" + io.readerName());
            String omapiEid = io.getEid();
            log.add("OMAPI_EID=" + omapiEid);

            Object apduProxy = new LpacApdu(io.rawSession()).newProxy(lpacLoader);
            Object httpProxy = new LpacHttp().newProxy(respClass, lpacLoader);

            long handle = ((Number) callJni("createContext",
                    new Class<?>[]{ byte[].class,
                            LpacApdu.findInterface("net.typeblog.lpac_jni.ApduInterface", lpacLoader),
                            LpacApdu.findInterface("net.typeblog.lpac_jni.HttpInterface", lpacLoader) },
                    new Object[]{ Tlv.unhex(EuiccIo.AID_ISDR_HEX), apduProxy, httpProxy })).longValue();
            log.add("CONTEXT=" + handle);
            if (handle == 0) return "ERROR=createContext 返回 0";
            log.add("STEP=euiccInit");

            int rc = ((Number) callJni("euiccInit",
                    new Class<?>[]{ long.class }, new Object[]{ handle })).intValue();
            log.add("EUICC_INIT=" + rc);
            if (rc != 0) return "ERROR=euiccInit rc=" + rc;

            log.add("STEP=es10cGetEid");
            Object lpacEid = callJni("es10cGetEid",
                    new Class<?>[]{ long.class }, new Object[]{ handle });
            log.add("LPAC_EID=" + lpacEid);

            long head = ((Number) callJni("es10cGetProfilesInfo",
                    new Class<?>[]{ long.class }, new Object[]{ handle })).longValue();
            int n = 0;
            if (head != 0) {
                long cur = head;
                while (cur != 0 && n < 50) {
                    log.add("LPAC_P" + n + "=" + callJni("profileGetIccid",
                            new Class<?>[]{ long.class }, new Object[]{ cur }));
                    cur = ((Number) callJni("profilesNext",
                            new Class<?>[]{ long.class }, new Object[]{ cur })).longValue();
                    n++;
                }
                callJni("profilesFree", new Class<?>[]{ long.class }, new Object[]{ head });
            }
            log.add("LPAC_COUNT=" + n);

            try { callJni("euiccFini", new Class<?>[]{ long.class }, new Object[]{ handle }); }
            catch (Throwable ignored) {}
            try { callJni("destroyContext", new Class<?>[]{ long.class }, new Object[]{ handle }); }
            catch (Throwable ignored) {}

            boolean same = lpacEid != null && String.valueOf(lpacEid).equalsIgnoreCase(omapiEid);
            return (same ? "RESULT=ok" : "RESULT=mismatch")
                 + "\nCMD=lpactest\nEID_MATCH=" + same + "\nPROFILES=" + n;
        } finally {
            io.close();
        }
    }

    /**
     * 设置配置昵称（ES10c SetNickname）。
     *
     * 走 lpac 而非自行拼 APDU：SGP.22 的 SetNickname 报文含 ICCID、昵称长度等
     * 细节约束（昵称须 UTF-8 且不超过 64 字节），交给 lpac 的已验证实现更可靠。
     *   LpacJni.es10cSetNickname(handle, iccid, nickNullTerminated: ByteArray): Int
     * 注意昵称以 NUL 结尾的字节数组传入（字段名 nickNullTerminated 即此含义）。
     *
     * @param nickname 新昵称；空字符串表示清除昵称
     */
    String setNickname(String iccid, String nickname, List<String> log, int slotIndex) throws Exception {
        if (iccid == null || iccid.trim().isEmpty()) return "ERROR=缺少 ICCID";
        String err = prepare();
        if (err != null) return "ERROR=" + err;

        byte[] nickBytes = (nickname == null ? "" : nickname).getBytes("UTF-8");
        if (nickBytes.length > 64) return "ERROR=昵称过长（UTF-8 需 ≤64 字节，当前 "
                + nickBytes.length + " 字节）";

        EuiccIo io = new EuiccIo(ctx, slotIndex);
        io.open();
        try {
            log.add("READER=" + io.readerName() + " (reader 名)");
            Object apduProxy = new LpacApdu(io.rawSession()).newProxy(lpacLoader);
            Object httpProxy = new LpacHttp().newProxy(respClass, lpacLoader);

            long handle = ((Number) callJni("createContext",
                    new Class<?>[]{ byte[].class,
                            LpacApdu.findInterface("net.typeblog.lpac_jni.ApduInterface", lpacLoader),
                            LpacApdu.findInterface("net.typeblog.lpac_jni.HttpInterface", lpacLoader) },
                    new Object[]{ Tlv.unhex(EuiccIo.AID_ISDR_HEX), apduProxy, httpProxy })).longValue();
            if (handle == 0) return "ERROR=createContext 返回 0";
            log.add("CONTEXT=" + handle);

            int rc = ((Number) callJni("euiccInit",
                    new Class<?>[]{ long.class }, new Object[]{ handle })).intValue();
            log.add("EUICC_INIT=" + rc);
            if (rc != 0) return "ERROR=euiccInit rc=" + rc;

            // 附 NUL 结尾（lpac 接口为 nickNullTerminated）
            byte[] withNul = new byte[nickBytes.length + 1];
            System.arraycopy(nickBytes, 0, withNul, 0, nickBytes.length);
            withNul[nickBytes.length] = 0;

            int setRc = ((Number) callJni("es10cSetNickname",
                    new Class<?>[]{ long.class, String.class, byte[].class },
                    new Object[]{ handle, iccid.trim(), withNul })).intValue();
            log.add("SET_NICK_RC=" + setRc);

            try { callJni("euiccFini", new Class<?>[]{ long.class }, new Object[]{ handle }); }
            catch (Throwable ignored) {}
            try { callJni("destroyContext", new Class<?>[]{ long.class }, new Object[]{ handle }); }
            catch (Throwable ignored) {}

            if (setRc != 0) return "ERROR=设置昵称失败 rc=" + setRc;
            return "RESULT=ok\nCMD=nickname\nICCID=" + iccid.trim()
                 + "\nNICK=" + (nickname == null ? "" : nickname);
        } finally {
            io.close();
        }
    }

    /**
     * 执行下载。
     * @param code  lpa:1$<smdp address>$<matchingId>
     * @param log   进度收集（供结果文件输出）
     */
    String download(String code, List<String> log, int slotIndex) throws Exception {
        String err = prepare();
        if (err != null) return "ERROR=" + err;

        // 解析 lpa:1$SMDP$MATCHID
        String body = code.substring(code.indexOf(':') + 1);
        String[] parts = body.split("\\$", -1);
        if (parts.length < 2) return "ERROR=激活码格式错误（应为 lpa:1$地址$匹配ID）";
        if (!"1".equals(parts[0].trim())) return "ERROR=仅支持 lpa:1 格式，当前: " + parts[0];
        String address = parts[1].trim();
        String matchingId = parts.length > 2 ? parts[2].trim() : null;
        if (matchingId != null && matchingId.isEmpty()) matchingId = null;
        log.add("SMDP=" + address);
        log.add("MATCHING_ID=" + (matchingId == null ? "(空)" : matchingId));

        // 打开 OMAPI 会话（复用 EuiccIo 的探测逻辑，拿到可用的 reader）
        EuiccIo io = new EuiccIo(ctx, slotIndex);
        io.open();
        try {
            log.add("READER=" + io.readerName() + " (reader 名)");
            Object apduProxy = new LpacApdu(io.rawSession()).newProxy(lpacLoader);
            Object httpProxy = new LpacHttp().newProxy(respClass, lpacLoader);

            // LpacJni.createContext(isdrAid, apduInterface, httpInterface): Long
            long handle = ((Number) callJni("createContext",
                    new Class<?>[]{ byte[].class,
                            LpacApdu.findInterface("net.typeblog.lpac_jni.ApduInterface", lpacLoader),
                            LpacApdu.findInterface("net.typeblog.lpac_jni.HttpInterface", lpacLoader) },
                    new Object[]{ Tlv.unhex(EuiccIo.AID_ISDR_HEX), apduProxy, httpProxy })).longValue();
            if (handle == 0) return "ERROR=createContext 返回 0（无法建立 lpac 上下文）";
            log.add("CONTEXT=" + handle);

            int rc = ((Number) callJni("euiccInit",
                    new Class<?>[]{ long.class }, new Object[]{ handle })).intValue();
            log.add("EUICC_INIT=" + rc);
            if (rc != 0) return "ERROR=euiccInit 失败 rc=" + rc;

            // 回调：记录进度。DownloadState 枚举名在 dex 中被混淆保护，
            // 直接 toString() 即可拿到可读的进度描述。
            final List<String> states = new ArrayList<String>();
            Object cb = Proxy.newProxyInstance(lpacLoader,
                    new Class<?>[]{ callbackClass },
                    new InvocationHandler() {
                        public Object invoke(Object p, Method m, Object[] a) {
                            if ("onStatusUpdate".equals(m.getName()) && a != null && a.length > 0) {
                                states.add(String.valueOf(a[0]));
                            }
                            return Boolean.TRUE;   // 返回 true 表示继续
                        }
                    });

            int drc = ((Number) callJni("downloadProfile",
                    new Class<?>[]{ long.class, String.class, String.class, String.class,
                                     String.class, callbackClass },
                    new Object[]{ handle, address, matchingId, null, null, cb })).intValue();
            log.add("DOWNLOAD_RC=" + drc);
            log.add("STATES=" + states.toString());
            log.add("HTTP_CALLS=" + LpacHttp.CALLS.size());
            for (String hc : LpacHttp.CALLS) log.add("HTTP|" + hc);

            if (drc != 0) {
                // 把 lpac 的错误码翻成可读文本
                try {
                    log.add("ERR_TEXT=" + callJni("downloadErrCodeToString",
                            new Class<?>[]{ int.class }, new Object[]{ drc }));
                } catch (Throwable ignored) {}
                return "ERROR=下载失败 rc=" + drc;
            }

            try { callJni("euiccFini", new Class<?>[]{ long.class }, new Object[]{ handle }); }
            catch (Throwable ignored) {}
            try { callJni("destroyContext", new Class<?>[]{ long.class }, new Object[]{ handle }); }
            catch (Throwable ignored) {}

            return "RESULT=ok\nCMD=download\nCODE=" + code;
        } finally {
            io.close();
        }
    }
}
