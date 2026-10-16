# Lino eUICC Control

面向联发科/展锐 UFI（MiFi）设备的一体化 eSIM 管理方案：**ufitools 插件 + Magisk 模块**。

插件提供图形界面，模块以特权身份直连 eUICC（OMAPI + ES10c）并复用 OpenEUICC 的
`liblpac-jni.so` 完成 ES9+ 下载。全部操作经 root shell 下发，**不做 UI 自动化**，
不受屏幕旋转/锁屏影响。

## 功能

| 功能 | 实现通道 | 状态 |
|---|---|---|
| 配置列表（ICCID / 昵称 / 提供商 / 状态） | OMAPI + ES10c `BF2D` | ✅ 实测通过 |
| 启用 / 切换配置 | ES10c `BF31` | ✅ 实测通过 |
| 禁用配置 | ES10c `BF32` | ✅ 实测通过 |
| 删除配置（ICCID 二次确认） | ES10c `BF33` | ⚠️ 拦截逻辑已验证，实际删除未执行 |
| 自定义名称（改名 / 清除） | lpac `es10cSetNickname` | ✅ 实测通过 |
| 下载并安装配置（无 UI） | lpac ES9+ | ✅ 实测通过 |
| 扫码填入激活码 | 本地 jsQR 解码 | ✅ 离线验证通过 |
| 卡槽识别 / 双卡槽位选择 | OMAPI reader 名映射 | ✅ 单卡实测，双卡逻辑推演 |

## 目录结构

```
plugin/            ufitools 插件（拖入即用）
  Lino-euicc-control_v1.6.js    最新
  Lino-euicc-control_v1.2.js    ...
module/
  src/             特权 APK 源码（Java）
  build-files/     Magisk 模块骨架（module.prop / service.sh / 白名单）
  releases/        可直接刷入的模块 zip
docs/              开发过程中的技术分析
```

## 安装

1. **先刷模块并重启**（插件依赖它）
   ```
   module/releases/Lino-euicc-control-vX.Y.zip  →  Magisk 刷入 → 重启
   ```
2. **再装插件**：把 `plugin/Lino-euicc-control_vX.Y.js` 拖入 ufitools

重启后用 `pm path kano.euiccctl` 确认应用位于 `/system/priv-app/`。

## 技术要点

### 为何用特权 APK 而非纯命令行

设备的 eUICC 是**可插拔实体 eSIM**（框架视为 `isEmbedded=false`），导致：
- `EuiccCardManager` 的写接口返回 `-3`（`RESULT_INVALID_EID`）
- 从 `app_process` 调 OMAPI 报 `SecurityException`（缺应用身份）

因此需要一个 priv-app，配合 `privapp_whitelist` 授予
`WRITE_EMBEDDED_SUBSCRIPTIONS` / `SECURE_ELEMENT_PRIVILEGED_OPERATION` 等权限。

### 为何下载要复用 lpac

系统 `EuiccService`（由 OpenEUICC 提供）**未实现** `onDownloadSubscription`
（上游源码即如此），调用只会得到 `UnsupportedOperationException`。
其下载向导 `DownloadWizardActivity` 存在竞态缺陷（见 docs），外部唤起不稳定。

故直接驱动 `liblpac-jni.so`：
```
liblpac-jni.so  NEEDED 仅 libc/libm/libdl（TLS 静态链接），141 个 JNI 导出
createContext(isdrAid, apduInterface, httpInterface) → long
downloadProfile(handle, address, matchingId, imei, confirmationCode, callback) → int
```
lpac 以依赖注入方式设计 —— C 库负责 SGP.22 协议，**APDU 与 HTTP 通道由调用方提供**。
本项目实现 `LpacApdu`（OMAPI）与 `LpacHttp`（HTTPS）两个桥接即可复用完整 ES9+。

### 踩过的坑（源码中均有注释）

- Kotlin `object` 的 `external fun` 编译为**实例方法**，反射须经 `INSTANCE` 字段传单例，
  否则 `NullPointerException: null receiver`
- 原生库与 ClassLoader 强绑定，`.so` 一旦被某个 loader 打开就不能再被另一个加载；
  loader 必须进程内单例
- SM-DP+ 使用 GSMA RSP2 专属 PKI（不在公共信任库），且
  **`HttpsURLConnection` 装自定义 SSLSocketFactory 后会被服务器拒绝（HTTP 500）**，
  改用 `SSLSocket` 手写 HTTP 才通
- ES10c 的 `EnableProfile` 报文里 `refreshFlag(81)` 必须在 `A0` **之外**
- OMAPI 的 `SEService` 必须 `shutdown()`，否则同进程二次使用报 `null receiver`

## 依赖与致谢

- [OpenEUICC](https://github.com/estkme-group/openeuicc) —— 提供 `liblpac-jni.so` 与 LPA 实现
- [jsQR](https://github.com/cozmo/jsQR) —— 二维码解码（已内嵌进插件）

`module/build-files/system/priv-app/OpenEUICC/` 中的文件来自 OpenEUICC 项目，
版权归其作者所有，请遵守其许可证。

## 已知限制

- **删除配置**不可逆，实际删除操作未在本项目测试中执行过
- 切换 profile 后系统 `EuiccManager` 缓存可能滞后，导致「下载」短暂报 `isEnabled=false`；
  等待或重启恢复
- 双卡双 eUICC 场景未经真机验证（测试设备只有一张 eUICC）
