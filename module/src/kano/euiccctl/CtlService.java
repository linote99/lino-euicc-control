package kano.euiccctl;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.telephony.euicc.EuiccManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KanoEuiccCtl —— 特权 eSIM 控制服务（配合 ufitools 插件）
 *
 * 插件以 root 调用:
 *   am startservice -n kano.euiccctl/.CtlService --es cmd <命令> [--es ...]
 * 结果写入 files/result.txt（覆盖写），插件用 root cat 读回。
 *
 * 命令:
 *   info                                 EID / 开关状态 / profile 列表
 *   list                                 profile 列表
 *   enable  --es iccid <ICCID>           启用
 *   disable --es iccid <ICCID>           禁用（会断网）
 *   delete  --es iccid <ICCID> --es confirm <同一 ICCID>
 *                                        删除；必须在 confirm 中重复目标 ICCID，
 *                                        不匹配则拒绝（硬校验，绕过插件也无法误删）
 *   download --es code <lpa:1$...>       经 lpac 下载并安装配置（无 UI）
 *   nickname --es iccid <ICCID> --es nick <名称>   设置/清除配置昵称（nick 空串=清除）
 *   slot                                 卡槽识别（物理槽数 / 端口可用性 / 本卡所在槽 / 卡可达性）
 *   probe                                探测 Enable/Disable 报文结构（用不存在的 ICCID，只读安全）
 *   raw --es apdu <HEX>                  发送任意原始 APDU（手工验证用）
 *
 * 为何用 Service 而非 BroadcastReceiver: Android 8+ 限制后台广播，
 * shell 发起的 startService 不受此限且可显式指定组件。
 *
 * 通道分工:
 *   info/list/enable/disable/delete → OMAPI + ES10c 直连 eUICC
 *     （本机为可插拔实体 eSIM，框架视为 isEmbedded=false，
 *       EuiccCardController 返回 -3，故走 OMAPI；特权应用可绕过 ARA-M）
 *   download → 直接驱动 OpenEUICC 的 liblpac-jni.so（无 UI）。
 *     系统 EuiccService(OpenEUICC) 未实现 onDownloadSubscription；其下载向导
 *     (DownloadWizardActivity) 又存在连接回调竞态、外部唤起不稳定。
 *     故复用 lpac 的 ES9+ 实现：由本 APK 注入 OMAPI 版 ApduInterface 与
 *     HttpsURLConnection 版 HttpInterface，C 库负责协议逻辑。
 */
public class CtlService extends Service {

    static final String TAG = "KanoEuiccCtl";
    static final String RESULT_FILE = "result.txt";

    /** 同一时刻只处理一条命令，避免并发写结果互相覆盖 */
    static final AtomicBoolean BUSY = new AtomicBoolean(false);

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(final Intent intent, int flags, int startId) {
        final String cmd = intent == null ? "info" : intent.getStringExtra("cmd");
        final Intent in = intent == null ? new Intent() : new Intent(intent);

        if (!BUSY.compareAndSet(false, true)) {
            write(this, "ERROR=busy: 上一条命令仍在执行");
            stopSelf();
            return START_NOT_STICKY;
        }

        new Thread(new Runnable() {
            public void run() {
                String out;
                try {
                    out = dispatch(CtlService.this, cmd, in);
                } catch (Throwable t) {
                    Throwable c = t;
                    while (c.getCause() != null) c = c.getCause();
                    out = "ERROR=" + c.getClass().getSimpleName() + ": " + c.getMessage();
                } finally {
                    BUSY.set(false);
                }
                write(CtlService.this, out);
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    // ---------------- 命令分发 ----------------

    static String dispatch(Context ctx, String cmd, Intent in) throws Exception {
        if (cmd == null) cmd = "info";
        String iccid = in.getStringExtra("iccid");

        // 可选槽位过滤：--ei slot <0-based simSlotIndex>。
        // 省略(-1)则沿用自动探测（第一张可用的 eUICC），保持向后兼容。
        // 双卡双 eUICC 时必须显式指定，否则行为不确定（先到先得）。
        int wantSlot = in.getIntExtra("slot", -1);

        // ---- 下载：直接驱动 lpac（无 UI，绕开有缺陷的下载向导）----
        if ("download".equals(cmd)) {
            String code = in.getStringExtra("code");
            if (code == null || code.trim().isEmpty()) return "ERROR=download 需要 --es code <lpa:1$...>";
            java.util.List<String> log = new java.util.ArrayList<String>();
            String r;
            try {
                r = new LpacBridge(ctx).download(code.trim(), log, wantSlot);
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                r = "ERROR=" + c.getClass().getSimpleName() + ": " + c.getMessage();
            }
            StringBuilder sb = new StringBuilder();
            for (String l : log) sb.append("LOG|").append(l).append('\n');
            if (!r.startsWith("ERROR=")) {
                // 成功后附带最新 profile 列表，便于插件直接渲染
                try {
                    EuiccIo io2 = new EuiccIo(ctx, wantSlot);
                    io2.open();
                    try {
                        sb.append("EID=").append(io2.getEid()).append('\n');
                        java.util.List<EuiccIo.Profile> ps = io2.getProfiles();
                        sb.append("COUNT=").append(ps.size()).append('\n');
                        for (EuiccIo.Profile pf : ps) sb.append("P|").append(pf.describe()).append('\n');
                    } finally { io2.close(); }
                } catch (Throwable ignored) {}
            }
            sb.append(r);
            return sb.toString().trim();
        }

        // ---- 删除：硬性二次确认 ----
        if ("delete".equals(cmd)) {
            String confirm = in.getStringExtra("confirm");
            if (iccid == null || iccid.trim().isEmpty()) return "ERROR=delete 需要 --es iccid <ICCID>";
            if (confirm == null || !confirm.trim().equals(iccid.trim())) {
                return "ERROR=删除已拒绝: confirm 必须与目标 ICCID 完全一致。"
                     + "目标=" + iccid + " 确认=" + (confirm == null ? "(空)" : confirm);
            }
        }

        // ---- 其余走 OMAPI + ES10c ----
        EuiccIo io = new EuiccIo(ctx, wantSlot);
        try {
            io.open();

            if ("info".equals(cmd)) {
                StringBuilder sb = new StringBuilder();
                sb.append("EID=").append(io.getEid()).append('\n');
                List<EuiccIo.Profile> ps = io.getProfiles();
                sb.append("COUNT=").append(ps.size()).append('\n');
                for (EuiccIo.Profile p : ps) sb.append("P|").append(p.describe()).append('\n');
                return sb.toString().trim();
            }

            if ("list".equals(cmd)) {
                List<EuiccIo.Profile> ps = io.getProfiles();
                StringBuilder sb = new StringBuilder();
                sb.append("COUNT=").append(ps.size()).append('\n');
                for (EuiccIo.Profile p : ps) sb.append("P|").append(p.describe()).append('\n');
                return sb.toString().trim();
            }

            if ("enable".equals(cmd) || "disable".equals(cmd) || "delete".equals(cmd)) {
                if (iccid == null || iccid.trim().isEmpty()) return "ERROR=" + cmd + " 需要 --es iccid <ICCID>";
                if ("enable".equals(cmd))  io.enableProfile(iccid.trim());
                if ("disable".equals(cmd)) io.disableProfile(iccid.trim());
                if ("delete".equals(cmd))  io.deleteProfile(iccid.trim());

                // 回读确认结果
                String eid = "";
                try { eid = io.getEid(); } catch (Throwable ignored) {}
                List<EuiccIo.Profile> ps = io.getProfiles();
                StringBuilder sb = new StringBuilder();
                sb.append("RESULT=ok").append('\n');
                sb.append("CMD=").append(cmd).append('\n');
                sb.append("ICCID=").append(iccid.trim()).append('\n');
                sb.append("EID=").append(eid).append('\n');
                sb.append("COUNT=").append(ps.size()).append('\n');
                for (EuiccIo.Profile p : ps) sb.append("P|").append(p.describe()).append('\n');
                return sb.toString().trim();
            }

            // lpac 桥接自检（不含网络，验证类加载/反射/OMAPI 回调链路）
            if ("lpactest".equals(cmd)) {
                java.util.List<String> lg = new java.util.ArrayList<String>();
                String r;
                try { r = new LpacBridge(ctx).selfTest(lg, wantSlot); }
                catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    r = "ERROR=" + c.getClass().getSimpleName() + ": " + c.getMessage();
                }
                StringBuilder sb = new StringBuilder();
                for (String l : lg) sb.append("LOG|").append(l).append('\n');
                sb.append(r);
                return sb.toString().trim();
            }

            // 卡槽识别：物理槽位 / 端口 / eUICC 可见性
            if ("slot".equals(cmd)) {
                return slotInfo(ctx, io, wantSlot);
            }

            // 设置配置昵称
            if ("nickname".equals(cmd) || "rename".equals(cmd)) {
                String iccid2 = in.getStringExtra("iccid");
                if (iccid2 == null || iccid2.trim().isEmpty()) return "ERROR=nickname 需要 --es iccid <ICCID>";
                String nick = in.getStringExtra("nick");
                if (nick == null) nick = "";
                java.util.List<String> lg = new java.util.ArrayList<String>();
                String r;
                try { r = new LpacBridge(ctx).setNickname(iccid2.trim(), nick, lg, wantSlot); }
                catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    r = "ERROR=" + c.getClass().getSimpleName() + ": " + c.getMessage();
                }
                StringBuilder sb = new StringBuilder();
                for (String l : lg) sb.append("LOG|").append(l).append('\n');
                if (!r.startsWith("ERROR=")) {
                    // 回读最新列表，便于插件直接刷新界面
                    try {
                        EuiccIo io3 = new EuiccIo(ctx, wantSlot);
                        io3.open();
                        try {
                            sb.append("EID=").append(io3.getEid()).append('\n');
                            java.util.List<EuiccIo.Profile> ps = io3.getProfiles();
                            sb.append("COUNT=").append(ps.size()).append('\n');
                            for (EuiccIo.Profile pf : ps) sb.append("P|").append(pf.describe()).append('\n');
                        } finally { io3.close(); }
                    } catch (Throwable ignored) {}
                }
                sb.append(r);
                return sb.toString().trim();
            }

            // 协议探测：用于定位 Enable/Disable 的正确报文结构（只读安全）
            if ("probe".equals(cmd)) {
                StringBuilder sb = new StringBuilder();
                sb.append("EID=").append(io.getEid()).append('\n');
                for (String line : io.probeSwitchStructures()) sb.append(line).append('\n');
                return sb.toString().trim();
            }

            // 发送原始 APDU（hex 字符串），用于手工验证
            if ("raw".equals(cmd)) {
                String hexApdu = in.getStringExtra("apdu");
                if (hexApdu == null || hexApdu.trim().isEmpty()) return "ERROR=raw 需要 --es apdu <HEX>";
                return "EID=" + io.getEid() + "\n" + io.rawApdu(hexApdu.trim());
            }

            return "ERROR=unsupported cmd: " + cmd;
        } finally {
            io.close();
        }
    }


    // ---------------- 卡槽识别 ----------------

    /**
     * 汇总卡槽信息。
     *
     * 数据来源（多路交叉，避免单一来源缺失）：
     *   1) TelephonyManager.getPhoneCount()          —— 物理槽数量
     *   2) EuiccManager.isSimPortAvailable(port)     —— 各端口是否可用
     *   3) SubscriptionManager 的活跃订阅 simSlotIndex —— 本卡所在槽
     *   4) OMAPI reader 名称 / 逻辑通道是否可达        —— 卡实际能否被访问
     *
     * 说明：本机 slot0 为空、eUICC 在 slot1。OMAPI 不直接暴露物理槽号，
     * 故以订阅的 simSlotIndex 作为权威判断，并用「逻辑通道能否打开」验证一致性。
     */
    static String slotInfo(Context ctx, EuiccIo io, int wantSlot) {
        StringBuilder sb = new StringBuilder();
        try {
            android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            int phones = tm == null ? -1 : tm.getPhoneCount();
            sb.append("PHONE_COUNT=").append(phones).append('\n');

            // isSimPortAvailable 需要调用方具备特定电话权限；本应用虽为 priv-app 并已授予
            // READ_PRIVILEGED_PHONE_STATE，实测该 API 仍抛 SecurityException
            // （AppOps 报 "Specified package android under uid ... but it is not"）。
            // 该字段仅作补充信息，SUB_SLOT 已给出权威槽位，故失败时直接省略，不写入噪音。
            EuiccManager em = (EuiccManager) ctx.getSystemService(Context.EUICC_SERVICE);
            if (em != null) {
                int okPorts = 0;
                for (int port = 0; port < Math.max(phones, 2); port++) {
                    try {
                        boolean a = em.isSimPortAvailable(port);
                        sb.append("PORT_").append(port).append("_AVAILABLE=").append(a).append('\n');
                        if (a) okPorts++;
                    } catch (Throwable ignored) { /* 无权限，跳过该端口不报错 */ }
                }
                sb.append("PORT_PROBE_OK=").append(okPorts > 0).append('\n');
            }
        } catch (Throwable t) {
            sb.append("TM_ERROR=").append(t.getMessage()).append('\n');
        }

        // 活跃订阅的槽位
        try {
            android.telephony.SubscriptionManager sm =
                android.telephony.SubscriptionManager.from(ctx);
            java.util.List<android.telephony.SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list != null) {
                for (android.telephony.SubscriptionInfo si : list) {
                    // 直接给出原始 simSlotIndex（0-based），不做 +1 换算。
                    sb.append("SUB_SLOT=").append(si.getSimSlotIndex())
                      .append(";ICCID=").append(si.getIccId())
                      .append(";EMBEDDED=").append(si.isEmbedded())
                      .append(";CARRIER=").append(si.getCarrierName())
                      .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("SUB_ERROR=").append(t.getMessage()).append('\n');
        }

        // 卡内实际可见性（OMAPI 通道已由上层打开）
        try {
            String eid = io.getEid();
            sb.append("EUICC_ACCESSIBLE=true").append('\n');
            sb.append("READER=").append(io.readerName()).append('\n');
            sb.append("EID=").append(eid).append('\n');
            int n = 0;
            for (EuiccIo.Profile p : io.getProfiles()) if (p.enabled()) n++;
            sb.append("ACTIVE_PROFILES=").append(n).append('\n');
        } catch (Throwable t) {
            sb.append("EUICC_ACCESSIBLE=false").append('\n');
        }

        // 逐槽探测 eUICC 可用性：插件据此决定是否显示卡槽切换器。
        // 判定方式：对该槽单独尝试打开 ISD-R 逻辑通道（只读，不改动卡片）。
        int euiiccSlots = 0;
        try {
            android.telephony.TelephonyManager tm2 =
                (android.telephony.TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            int n = tm2 == null ? 2 : Math.max(tm2.getPhoneCount(), 2);
            for (int sl = 0; sl < n; sl++) {
                boolean ok = false;
                EuiccIo probe = null;
                try {
                    probe = new EuiccIo(ctx, sl);
                    probe.open();
                    ok = probe.getEid() != null;
                } catch (Throwable ignored) {
                } finally {
                    if (probe != null) probe.close();
                }
                if (ok) euiiccSlots++;
                sb.append("SLOT_EUICC_").append(sl).append('=').append(ok).append('\n');
            }
        } catch (Throwable t) {
            sb.append("SLOT_PROBE_ERROR=").append(t.getMessage()).append('\n');
        }
        sb.append("EUICC_SLOT_COUNT=").append(euiiccSlots).append('\n');
        sb.append("SELECTED_SLOT=").append(io.readerName() == null ? "?" : io.readerName()).append('\n');
        return sb.toString().trim();
    }

    // ---------------- 下载（委托系统 EuiccService）----------------

    // ---------------- 结果落盘 ----------------

    static void write(Context ctx, String text) {
        try {
            File f = new File(ctx.getFilesDir(), RESULT_FILE);
            FileOutputStream fos = new FileOutputStream(f, false);
            Writer w = new OutputStreamWriter(fos, "UTF-8");
            try { w.write(text); } finally { w.close(); }
            f.setReadable(true, false);
            Log.i(TAG, "result: " + text.replace('\n', '|'));
        } catch (Throwable t) {
            Log.e(TAG, "write failed", t);
        }
    }
}
