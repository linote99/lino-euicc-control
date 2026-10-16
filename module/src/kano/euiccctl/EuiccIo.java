package kano.euiccctl;

import android.content.Context;
import android.se.omapi.Channel;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * EUICC 卡片 I/O —— 经 OMAPI 直连 eUICC，用 ES10c 命令读写。
 *
 * 为何用 OMAPI 而非 EuiccManager:
 *   本机这张是可插拔实体 eSIM，框架将其视为 isEmbedded=false，
 *   EuiccCardController 一律返回 -3 (RESULT_INVALID_EID)，写路径不通。
 *   OMAPI + ES10c 是 OpenEUICC 自身使用的通道（日志可见
 *   "Access = ALLOWED, Reason = privilege application"），特权应用可用。
 *
 * ES10c 命令（ISD-R 安全域）：
 *   GetEID           80 E2 91 00 06 BF 3E 03 5C 01 5A
 *   GetProfiles      80 E2 91 00 03 BF 2D 00
 *   EnableProfile    80 E2 91 00 0B BF 31 09 A0 06 5A 04 <iccid-bcd>
 *   DisableProfile   80 E2 91 00 0B BF 32 09 A0 06 5A 04 <iccid-bcd>
 *   DeleteProfile    80 E2 91 00 0B BF 33 09 A0 06 5A 04 <iccid-bcd>
 */
final class EuiccIo {

    static final String TAG = "KanoEuiccCtl";

    /** ISD-R AID */
    static final byte[] AID_ISDR = hex("A0000005591010FFFFFFFF8900000100");
    /** 同上，hex 形式（供 lpac 桥接使用） */
    static final String AID_ISDR_HEX = "A0000005591010FFFFFFFF8900000100";

    /** 本机 eUICC 所在卡槽的 ISD-R AID 兜底列表（不同厂商略有差异） */
    static final String[] AID_CANDIDATES = {
        "A0000005591010FFFFFFFF8900000100",
        "A0000005591010000000008900000300",
        "A0000005591010FFFFFFFF8900050500",
        "A0000005591010FFFFFFFF8900000177",
    };

    static final byte[] APDU_GET_EID = hex("80E2910006BF3E035C015A");
    static final byte[] APDU_GET_PROFILES = hex("80E2910003BF2D00");

    private final Context ctx;
    private SEService se;
    private Session session;
    private Channel channel;

    /** 目标槽位（0-based simSlotIndex）；-1 表示不限，自动探测第一张可用的 eUICC */
    private int wantSlot = -1;
    /** 实际选中的 reader 名（诊断用） */
    private String usedReader = null;

    EuiccIo(Context c) { ctx = c; }

    EuiccIo(Context c, int slotIndex) { ctx = c; wantSlot = slotIndex; }

    /** 已选中的 reader 名，如 "SIM2"；未打开时为 null */
    String readerName() { return usedReader; }

    /**
     * 由 OMAPI reader 名解析 1-based 物理槽号，再转 0-based simSlotIndex。
     *
     * 实测映射：reader 名形如 "SIM1"/"SIM2"，其中的数字即 1-based 物理槽号；
     * 而 simSlotIndex 是 0-based，故 simSlotIndex = 数字 - 1。
     * （本机验证：eUICC 在 simSlotIndex=1，日志 reader=SIM2。）
     * 无法解析时返回 -1。
     */
    private static int slotIndexOfReader(String readerName) {
        if (readerName == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(readerName);
        String num = null;
        while (m.find()) num = m.group(1);   // 取最后一段数字
        if (num == null) return -1;
        try { return Integer.parseInt(num) - 1; } catch (Throwable t) { return -1; }
    }

    // ---------------- 连接管理 ----------------

    /**
     * 返回底层 OMAPI Session，供 lpac 桥接自行管理逻辑通道。
     * 注意：lpac 会自行 open/close 通道，故调用方在 lpac 工作期间
     * 不应再使用本对象的 channel；两者共用同一个 Session 是安全的，
     * OMAPI 允许同一 Session 上并存多个逻辑通道。
     */
    Object rawSession() { return session; }

    void open() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        Executor ex = new Executor() {
            public void execute(Runnable r) { new Thread(r).start(); }
        };
        se = new SEService(ctx, ex, new SEService.OnConnectedListener() {
            public void onConnected() { latch.countDown(); }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) throw new Exception("OMAPI SEService 连接超时");
        if (!se.isConnected()) throw new Exception("OMAPI 未连接");

        Reader[] readers = se.getReaders();
        if (readers == null || readers.length == 0) throw new Exception("没有可用的 SE Reader");

        Exception last = null;
        for (Reader r : readers) {
            try {
                if (!r.isSecureElementPresent()) continue;
                // 槽位过滤：指定了 wantSlot 时，只尝试物理槽号匹配的 reader
                int slotOfReader = slotIndexOfReader(r.getName());
                if (wantSlot >= 0 && slotOfReader != wantSlot) {
                    Log.i(TAG, "跳过 reader=" + r.getName() + "（slot=" + slotOfReader
                            + "，目标 slot=" + wantSlot + "）");
                    continue;
                }
                session = r.openSession();
                for (String aidHex : AID_CANDIDATES) {
                    try {
                        channel = session.openLogicalChannel(hex(aidHex));
                        if (channel != null) {
                            usedReader = r.getName();
                            Log.i(TAG, "ISD-R ok, reader=" + r.getName() + " aid=" + aidHex
                                    + (wantSlot >= 0 ? " (指定 slot=" + wantSlot + ")" : ""));
                            return;
                        }
                    } catch (Throwable t) { last = new Exception("openLogicalChannel " + aidHex + " 失败", t); }
                    // 该 reader 不是 eUICC，清理会话后继续看下一个
                    try { session.close(); } catch (Throwable ignored) {}
                    session = null;
                }
            } catch (Throwable t) { last = new Exception("openSession 失败 reader=" + r.getName(), t); }
        }
        if (wantSlot >= 0) {
            throw new Exception("指定的卡槽 " + wantSlot + " 上没有可用的 eUICC"
                    + "（该槽可能为空、或插的不是 eUICC）");
        }
        throw last != null ? last : new Exception("未能打开任何 eUICC 逻辑通道");
    }

    /**
     * 释放 OMAPI 资源。
     *
     * 必须 shutdown() 掉 SEService —— 它内部通过 bindService 持有 ServiceConnection，
     * 不释放会在同一进程内遗留绑定（实测日志报 "has leaked ServiceConnection"），
     * 并导致后续再次 new SEService 时出现 "NullPointerException: null receiver"。
     */
    void close() {
        try { if (channel != null) channel.close(); } catch (Throwable ignored) {}
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        try { if (se != null) se.shutdown(); } catch (Throwable ignored) {}
        channel = null; session = null; se = null;
    }

    /**
     * 发送 APDU 并原样返回响应（含末尾 2 字节 SW），不抛异常。
     * 用于协议探测：需要区分"结构错误(6A80)"与"内容错误(6A88)"。
     */
    byte[] transceiveRaw(byte[] apdu) throws Exception {
        if (channel == null) throw new Exception("通道未打开");
        byte[] r = channel.transmit(apdu);
        return r == null ? new byte[0] : r;
    }

    /** 取响应末尾 SW 两位十六进制；响应不足 2 字节时返回 "????" */
    static String sw(byte[] resp) {
        if (resp == null || resp.length < 2) return "????";
        return String.format("%02X%02X", resp[resp.length - 2] & 0xFF, resp[resp.length - 1] & 0xFF);
    }

    /** 发送 APDU，校验 SW=9000，返回数据段 */
    byte[] transceive(byte[] apdu) throws Exception {
        if (channel == null) throw new Exception("通道未打开");
        byte[] resp = channel.transmit(apdu);
        return checkSw(apdu, resp);
    }

    private static byte[] checkSw(byte[] apdu, byte[] resp) throws Exception {
        if (resp == null || resp.length < 2) throw new Exception("APDU 响应异常: " + Tlv.hex(resp));
        int n = resp.length;
        int sw1 = resp[n - 2] & 0xFF, sw2 = resp[n - 1] & 0xFF;
        if (sw1 != 0x90 || sw2 != 0x00) {
            // 61 XX: 有数据待取，用 GET RESPONSE
            if (sw1 == 0x61) {
                throw new NeedGetResponse(sw2);
            }
            throw new Exception(String.format("APDU 失败 SW=%02X%02X (cmd=%s)", sw1, sw2, Tlv.hex(apdu)));
        }
        byte[] data = new byte[n - 2];
        System.arraycopy(resp, 0, data, 0, n - 2);
        return data;
    }

    /** 61XX：需要 GET RESPONSE 取回剩余数据 */
    static final class NeedGetResponse extends Exception {
        final int len;
        NeedGetResponse(int l) { super("61" + String.format("%02X", l)); len = l; }
    }

    // ---------------- ES10c 命令 ----------------

    String getEid() throws Exception {
        byte[] d = transceive(APDU_GET_EID);
        Tlv t = Tlv.find(Tlv.parse(d), 0x5A);
        if (t == null) throw new Exception("GetEID 未返回 5A: " + Tlv.hex(d));
        // EID 是 16 字节二进制标识符，按 hex 呈现（不是 BCD！ICCID 才需要 BCD 解码）
        return Tlv.hex(t.value);
    }

    /**
     * 读取全部 profile。
     *
     * ES10c ProfileInfoListOk 实际层级（实测该卡）：
     *   BF2D
     *     A0                         ProfileInfoListOk
     *       E3 ...                   ProfileInfo × N
     *         5A     ICCID(BCD)
     *         4F     ISD-P AID
     *         9F70   ProfileState (1 byte: 1=enabled 0=disabled)  <-- 注意不是 nickname
     *         90     ProfileNickname (UTF8)                       <-- 注意不是 state
     *         91     ServiceProviderName (UTF8)
     *         92     ProfileName (UTF8)
     *         95     ProfileClass
     *
     * 字段语义经实机核对（与 OpenEUICC 界面一致）：
     *   9F70=01 且 90="Telna_Jetpac_kingking99"   -> 界面显示「已启用」
     *   9F70=00 且 90="Telna_Jetpac_liulihong9999" -> 界面显示「已禁用」
     * 早期版本把 90 当 state、9F70 当 nickname，导致 state 读成 'T'(0x54)。
     *  注意：不可用 find() 递归找 E3 —— 那会钻进 E3 内部把子字段也当 profile；
     *  必须逐层 parse，只在 A0 的直接子节点里取 E3。
     */
    List<Profile> getProfiles() throws Exception {
        byte[] d = transceive(APDU_GET_PROFILES);
        Tlv bf2d = Tlv.find(Tlv.parse(d), 0xBF2D);          // 顶层找 BF2D 无歧义
        if (bf2d == null) throw new Exception("GetProfiles 未返回 BF2D: " + Tlv.hex(d));

        // BF2D -> A0（有些实现省略 A0，则回退到 BF2D 本身）
        Tlv holder = bf2d;
        List<Tlv> lv1 = Tlv.parse(bf2d.value);
        for (Tlv t : lv1) {
            if (t.tag == 0xA0) { holder = t; break; }
        }

        List<Profile> out = new ArrayList<Profile>();
        for (Tlv e3 : Tlv.parse(holder.value)) {            // 只取直接子节点
            if (e3.tag != 0xE3) continue;
            Profile pf = new Profile();
            for (Tlv f : Tlv.parse(e3.value)) {
                switch (f.tag) {
                    case 0x5A:   pf.iccid    = Tlv.bcd(f.value); break;
                    case 0x9F70: pf.state    = f.value.length > 0 ? (f.value[0] & 0xFF) : -1; break;
                    case 0x90:   pf.nickname = Tlv.ascii(f.value); break;
                    case 0x91:   pf.spn      = Tlv.ascii(f.value); break;
                    case 0x92:   pf.name     = Tlv.ascii(f.value); break;
                    default: break;
                }
            }
            out.add(pf);
        }
        return out;
    }

    /** ES10c EnableProfile (BF31) */
    void enableProfile(String iccid) throws Exception {
        transceive(buildSwitch(0xBF31, iccid));
    }

    /** ES10c DisableProfile (BF32) */
    void disableProfile(String iccid) throws Exception {
        transceive(buildSwitch(0xBF32, iccid));
    }

    /** ES10c DeleteProfile (BF33) */
    void deleteProfile(String iccid) throws Exception {
        transceive(buildSwitch(0xBF33, iccid));
    }

    /**
     * 构造 ES10c Enable/Disable/Delete 请求（BF31 / BF32 / BF33）。
     *
     * 报文结构（TLV 层层嵌套）：
     *   BF31/BF32/BF33
     *     A0                 ProfileOperationRequest
     *       5A               ICCID
     *         <ICCID BCD>
     * 字节级展开（ICCID=89103000000601264167，BCD 10 字节）：
     *   BF 32 | 11 | A0 0C | 5A 0A | 98 01 03 00 00 60 10 62 14 76 | 81 01 01
     *   tag      len  tag len  tag len  ICCID(10B BCD)                 81 01 01
     *   APDU: 80 E2 91 00 14 <上面的 body>   (Lc=0x14=20)
     *
     * 长度推导：A0 内容 = 5A 的 tag(1)+len(1)+ICCID(10) = 12(0x0C)；
     *           外层 tag 内容 = A0 整段(2+12) + refreshFlag(3) = 17(0x11)；
     *           Lc = 2 + 1 + 17 = 20(0x14)。
     *
     * ★ 关键：refreshFlag(81) 必须位于 A0 **之外**、作为外层 tag 的直接子节点。
     *   实机 probe 曾逐一验证 8 种候选结构，只有 "A0{5A} + 81 在外面" 返回 9000，
     *   其余（含把 81 放进 A0 内）全部 6A80。
     *
     * refreshFlag 语义：=1 请求 eUICC 自行触发 refresh，使变更立即生效。
     * DeleteProfile(BF33) 亦用同一布局。
     */
    private static byte[] buildSwitch(int tag, String iccid) throws Exception {
        byte[] icc = Tlv.toBcd(iccid);
        if (icc.length == 0) throw new Exception("ICCID 为空");

        // A0 内容仅含 5A(ICCID)
        int a0ContentLen = 2 + icc.length;
        byte[] a0 = new byte[2 + a0ContentLen];       // A0 + len + 5A..ICCID
        a0[0] = (byte) 0xA0;
        a0[1] = (byte) a0ContentLen;
        a0[2] = 0x5A;
        a0[3] = (byte) icc.length;
        System.arraycopy(icc, 0, a0, 4, icc.length);

        // 外层 tag 内容 = A0 整段 + refreshFlag(81 01 01)
        //   refreshFlag 必须在 A0 之外（实机 probe 结论）
        byte[] refresh = new byte[]{ (byte) 0x81, 0x01, 0x01 };
        byte[] body = new byte[3 + a0.length + refresh.length];
        body[0] = (byte) (tag >> 8);                  // 0xBF
        body[1] = (byte) (tag & 0xFF);                // 0x31/0x32/0x33
        body[2] = (byte) (a0.length + refresh.length);
        System.arraycopy(a0, 0, body, 3, a0.length);
        System.arraycopy(refresh, 0, body, 3 + a0.length, refresh.length);

        // APDU: CLA INS P1 P2 Lc <body>
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x80); o.write(0xE2); o.write(0x91); o.write(0x00);
        o.write(body.length);                         // Lc = body 长度
        o.write(body, 0, body.length);
        return o.toByteArray();
    }


    // ---------------- 协议探测（只读安全）----------------

    /**
     * 探测 EnableProfile/DisableProfile 的正确报文结构。
     *
     * 安全性：一律使用**不存在的 ICCID**(全 00)，因此即使结构被接受，
     * 卡片也只会回"找不到该 profile"类错误，绝不会真的切换/删除配置。
     * 借此区分两类失败：
     *   6A80 = 数据字段参数错误 → 结构不对（继续换结构）
     *   6A88/6A82 = 找不到引用数据 → **结构已被解析**，说明该结构正确
     */
    List<String> probeSwitchStructures() throws Exception {
        List<String> out = new ArrayList<String>();
        final String bogus = "00000000000000000000";   // 不存在的 ICCID
        byte[] icc = Tlv.toBcd(bogus);

        for (String[] v : switchVariants(icc)) {
            byte[] apdu = wrap(0xBF32, v[1]);
            byte[] resp;
            String err;
            try { resp = transceiveRaw(apdu); err = null; }
            catch (Throwable t) { resp = null; err = String.valueOf(t.getMessage()); }
            String swv = resp == null ? ("EXC:" + err) : sw(resp);
            String verdict = swv.startsWith("6A88") || swv.startsWith("6A82") || swv.startsWith("9000")
                    ? "  <== 结构被接受" : "";
            out.add(String.format("%-22s SW=%-8s %s%s", v[0], swv, Tlv.hex(apdu), verdict));
        }
        return out;
    }

    /** 生成候选 TLV 结构：[名称, A0内容(或空表示无A0)] */
    private static List<String[]> switchVariants(byte[] icc) {
        String iccidTlv = "5A" + String.format("%02X", icc.length) + Tlv.hex(icc);
        List<String[]> l = new ArrayList<String[]>();
        l.add(new String[]{"A0{5A,81=1}",   "A0" + len(iccidTlv + "810101") + iccidTlv + "810101"});
        l.add(new String[]{"A0{5A,81=0}",   "A0" + len(iccidTlv + "810100") + iccidTlv + "810100"});
        l.add(new String[]{"A0{5A} only",   "A0" + len(iccidTlv) + iccidTlv});
        l.add(new String[]{"5A,81 no-A0",   iccidTlv + "810101"});
        l.add(new String[]{"5A only",       iccidTlv});
        l.add(new String[]{"A0{5A},81 outside", "A0" + len(iccidTlv) + iccidTlv + "810101"});
        l.add(new String[]{"A0{5A,80=1}",   "A0" + len(iccidTlv + "800101") + iccidTlv + "800101"});
        l.add(new String[]{"A0{5A,82=1}",   "A0" + len(iccidTlv + "820101") + iccidTlv + "820101"});
        return l;
    }

    private static String len(String innerHex) {
        int n = innerHex.length() / 2;
        if (n < 0x80) return String.format("%02X", n);
        return String.format("81%02X", n);
    }

    private static byte[] hexToBytesUnchecked(String h) { return Tlv.unhex(h); }

    /** 用 tag + 已构造好的内部 hex 生成完整 APDU */
    private static byte[] wrap(int tag, String innerHex) {
        byte[] inner = Tlv.unhex(innerHex);
        byte[] body = new byte[3 + inner.length];
        body[0] = (byte) (tag >> 8);
        body[1] = (byte) (tag & 0xFF);
        body[2] = (byte) inner.length;
        System.arraycopy(inner, 0, body, 3, inner.length);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x80); o.write(0xE2); o.write(0x91); o.write(0x00);
        o.write(body.length);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    /** 发送任意原始 APDU（hex），返回 SW 与数据长度。用于手工探测。 */
    String rawApdu(String hexApdu) throws Exception {
        byte[] resp = transceiveRaw(Tlv.unhex(hexApdu));
        int dataLen = resp.length >= 2 ? resp.length - 2 : 0;
        return "SW=" + sw(resp) + " DATALEN=" + dataLen
             + (dataLen > 0 ? " DATA=" + Tlv.hex(java.util.Arrays.copyOf(resp, dataLen)) : "");
    }

    // ---------------- 数据模型 ----------------

    static final class Profile {
        String iccid = "";
        String nickname = "";
        String spn = "";
        String name = "";
        int state = -1;      // ES10c ProfileState: 0=disabled 1=enabled

        boolean enabled() { return state == 1; }

        String describe() {
            return "iccid=" + iccid
                 + ";nick=" + clean(nickname)
                 + ";spn=" + clean(spn)
                 + ";name=" + clean(name)
                 + ";state=" + state
                 + ";enabled=" + enabled();
        }
        static String clean(String s) { return s == null ? "" : s.replace(";", ",").replace("\n", " "); }
    }

    // ---------------- 工具 ----------------

    static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
