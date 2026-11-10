# EasyEUICC 报错分析（荣悦 E5）

日志文件：`/sdcard/Download/2026年10月15日 19_43_39 的日志.txt`（683 行，88 KB，时间跨度 19:30:35–19:31:59）
应用：`im.angry.easyeuicc` v1.6.2-**unpriv**（OpenEUICC），targetSdk 35
设备：荣悦 E5 / ums9621 / Android 13（SDK 33）

---

## 一、结论（一句话）

**这是"未提权版本 + ROM 无 eUICC 框架 + 卡槽 0 为空"三者叠加的预期行为，不是卡坏了，也不是日志里那些 ERROR 行导致的。**

日志里真正致命的只有一条：

```
EuiccChannelManager: Was able to open channel for logical slot 0, but the channel
is invalid (cannot get eID or profiles without errors). This slot might be broken, aborting.
```

即：**逻辑通道能打开，但拿不到 EID**。下面说明为什么。

---

## 二、报错时间线（从日志与实机复现还原）

| 时刻 | 事件 |
|---|---|
| 19:30:35 | App 进程 4258 启动 |
| 19:30:36.4 | 探测 slot 0：8 个 ISD-R AID 全部 `interface unavailable` |
| 19:30:36.7 | **slot 0 判定：通道 invalid，aborting** |
| 19:30:38.5 | slot 1 上 `Found eUICC on slot 1 port 0`（找到了！） |
| 19:30:38.5 | 紧接 `OMAPI APDU exception` / `IOException: Error in transmit()` |
| 19:30:38.5 | NPE 冒泡到 `EuiccManagementFragment$doRefresh`（UI 刷新崩溃） |
| 19:30:43 | `System.exit called, status: -1` |
| 19:30:43–19:31:57 | App 反复重启（PID 4258→4334→4405→4735→4844），每次同样失败 |

日志中 5 次启动、共 **16 次** `transmit` 异常、**3 次** `System.exit(-1)`。

---

## 三、根因分析

### 3.1 决定性证据：OMAPI 层其实**读到了 EID**

这是整个分析的关键。在 SE（SecureElement）服务侧日志里，APDU 是**成功**的：

```
SecureElementService: openLogicalChannel() AID = a0000005591  → Success. Channel: 1
SecureElement-AccessControlEnforcer: checkCommand() : Access = ALLOWED
Terminal-SIM2: Sent     :           ← ES10c GetEID
Terminal-SIM2:    ← 成功返回 EID + 9000
```

**EID 实际读出来`。
`9000` 是标准成功状态码，`bf3e125a10...` 正是 ES10c GetEID 的应答结构。

但 App 侧同时报：

```
E/OmapiApduInterface: OMAPI APDU exception
java.io.IOException: Error in transmit()
    at android.se.omapi.Channel.transmit(Channel.java:174)
    at im.angry.openeuicc.core.OmapiApduInterface.transmit(OmapiApduInterface.kt:66)
    at net.typeblog.lpac_jni.LpacJni.es10cGetEid(Native Method)
```

**卡和 SE 服务都正常，是"底层成功、上层抛异常"**。这说明问题出在 OMAPI 通道的**响应回传/生命周期**环节，而非卡本身。

### 3.2 通道泄漏（可疑点）

单次启动实测统计：

| 指标 | 次数 |
|---|---|
| `openLogicalChannel()` 调用 | 6 |
| 其中成功 | 3 |
| **`closeLogicalChannel()` 调用** | **0** |

日志中**完全没有通道关闭记录**。SIM 卡逻辑通道数量有限（通常 1–4 个），只开不关会导致后续 `openLogicalChannel` 失败——这与日志里 slot 1 反复 `unavailable` 吻合。

### 3.3 ROM 侧缺 eUICC 框架支持

实测（三条独立证据）：

```
telephony.euicc feature        : 0      ← 无 eUICC 特性声明
EuiccService 实现包            : 0      ← 无系统 eUICC 服务
dumpsys euicc 输出行数         : 0      ← 无 EuiccController
```

ROM 完全没有 eUICC 支持，只有第三方 `im.angry.easyeuicc`。这类 ROM 上 OMAPI/SE 链路往往是厂商自行拼装的，未经 eUICC 场景验证。

另外 SE HAL 是 **`secure_element@1.0`**（较老），而 App targetSdk 35、运行在 Android 13 上。

### 3.4 卡槽 0 为空 → 产生误导性报错

`gsm.sim.state` = `ABSENT,LOADED`：slot 0 空、slot 1 有卡。App 对空的 slot 0 也逐一探测 8 个 AID，全部失败后打印 `This slot might be broken`——**这条"卡槽可能损坏"是误导**，槽 0 本来就没插卡。

### 3.5 日志里可以忽略的行

以下都是噪音，与故障无关：

| 日志行 | 说明 |
|---|---|
| `Unable to open '/system_ext/framework/unisoc-framework.dm'` | 正常，DM 文件不存在 |
| `Could not write anonymous vdex ... unisoc-framework.vdex` | 系统分区只读，正常 |
| `avc: denied { map } ... unipnp_prop` | SELinux Permissive 下的审计记录，`permissive=1` 已放行 |
| `Could not write anonymous vdex` (E 级) | 无害 |
| `Parcel: Expecting binder but got null!` | 无害 |

---

## 四、修正我自己的分析：`uniber ACTION_RESET` 是红鲱鱼

我一开始怀疑内核 `uniber: ACTION_RESET triggered` 是 SIM 复位导致传输中断，**这个判断是错的**。

实测该事件**周期性触发**（约每 5 秒一次，与 `uniber: CPUs balance changed` 成对出现），是 CPU 调频/负载均衡的产物，与 SIM 无关：

```
[ 2694.898] uniber: CPUs balance changed
[ 2694.902] uniber: ACTION_RESET triggered
[ 2709.913] uniber: CPUs balance changed
[ 2709.917] uniber: ACTION_RESET triggered
```

排除该假设。

---

## 五、重要情况说明：测试期间卡槽状态发生变化

**测试过程中 SIM 卡槽状态从 `ABSENT,LOADED` 变成了 `ABSENT,ABSENT`**，即在排查期间 slot 1 的卡变为不可见。

- 发生时间：约 20:10–20:16 之间（我 19:49 首次读取时仍为 `ABSENT,LOADED`）
- 期间设备**未重启**（uptime 连续：1905s → 2911s）
- 变化后：`AT+CPIN?` → `CME ERROR 10`，`AT+CCID` 失败，所有 `AT+CSIM` 失败
- 但 Android 侧 `isub` 仍保留旧订阅记录（`iccId=89103000000601264167`），属陈旧缓存
- `/api/sim_slot` 返回 `-1`

**我无法确认这是我的操作导致的，还是物理拔卡/接触不良。** 我只做过只读 APDU 查询（`AT+CSIM` 选择 AID、读 EID），这类操作**理论上不会**移除卡片。但也存在其他可能：

1. 有人物理拔卡（设备在别处，我无法观察）
2. eSIM 卡接触不良/热插拔检测问题
3. 我高频反复启动 App 触发卡状态异常

**这一条需要你确认。** 如果是物理拔卡，则后续实测数据以 19:49 前的为准；如果是异常掉卡，那本身可能是另一个值得查的问题（且与 EasyEUICC 报错可能同源）。

---

## 六、建议的解决方向（按优先级）

### 1. 换用提权版（最可能直接解决）

当前是 `v1.6.2-unpriv`。非提权版走公开 OMAPI，功能受限。在已经 Magisk root 的设备上应使用 **priv 版**（装为系统应用或授予特权），可绕过 ARA-M 访问控制与部分通道限制。

设备已有 root 与 Magisk，具备条件：

```
Magisk 28103，模块 kano_ufi_tools_for_unisoc
SELinux: Permissive
```

### 2. 用 USB 读卡器通道

App 支持 `channel_type_usb`（设置里可见）。日志中也出现 `channel_type_usb` 字样。用 PC/SC 读卡器直连可**完全绕过 OMAPI 与 ROM 的 eUICC 缺失问题**，是最可靠的验证手段——如果 USB 通道能正常读 EID，就证明卡完全没问题，问题 100% 在手机的 OMAPI 链路。

### 3. 检查卡槽与插卡

- 确认目标是 slot 1（有卡），忽略 slot 0 的 "broken" 报错
- 若卡是实体 eSIM（如 5ber/9eSIM/ESTK 等），确认与 Android 13 + secure_element@1.0 的兼容性

> 顺带：日志里 App 探测的自定义 AID 解码后含 **`estkme`**（`A06573746B6D65...` → `estkme`），说明 App 按 ESTK 卡片的 AID 方案在探测，可判断这张卡是 **ESTK 系**的实体 eSIM 卡。

### 4. 若必须用 OMAPI，检查通道泄漏

SE 层"只开不关"（0 次 close）值得关注。可临时重启 `com.android.se` 或整机后**立即**首次启动 App，观察是否因通道未累积而成功——若首次能成功、多次失败，则通道泄漏成立。

---

## 七、复现方式（供你自行验证）

```bash
# 唤醒屏幕
adb shell input keyevent KEYCODE_WAKEUP

# 清日志并重启 App
adb shell am force-stop im.angry.easyeuicc
adb shell logcat -c
adb shell am start -n im.angry.easyeuicc/im.angry.openeuicc.ui.UnprivilegedMainActivity
sleep 15

# 看结果
adb shell logcat -d | grep -E "EuiccChannelManager|OmapiApdu|Error in transmit|System.exit"
```

判定要点：

- `Success. Channel` 出现 + `bf3e125a10...` 出现 = **SE 层读 EID 成功**
- 同时 `Error in transmit` 出现 = **上层 OMAPI 回传失败**（当前故障特征）

---

## 八、证据来源

- 日志文件：`/sdcard/Download/2026年10月15日 19_43_39 的日志.txt`
- 实机复现：`logcat -d`（19:53–20:13 多次抓取）
- SE 服务侧日志：`SecureElementService` / `SecureElement-Terminal-SIM2` / `SecureElement-AccessControlEnforcer`
- 卡状态：`getprop gsm.sim.state`、`dumpsys isub`、`AT+CPIN?`、`AT+CCID`
- ROM 能力：`pm list features`、`dumpsys euicc`、`lshal | grep secure_element`
- APK 分析：`base.apk` 解包（`classes*.dex`、`resources.arsc`、`lib/arm64-v8a/liblpac-jni.so`）
