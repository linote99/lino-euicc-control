#!/system/bin/sh
# Magisk 安装回调：设置 priv-app 及其原生库权限，否则系统不加载该应用。
#
# 本模块合并了两个应用：
#   KanoEuiccCtl  —— 配合 ufitools 插件，经 OMAPI+ES10c 直连 eUICC
#   OpenEUICC     —— 系统 EuiccService 实现（LPA），供「下载配置」流程使用

# KanoEuiccCtl（无原生库）
set_perm_recursive $MODPATH/system/priv-app/KanoEuiccCtl 0 0 0755 0644

# OpenEUICC（含 lib/arm64/liblpac-jni.so）
#   priv-app 目录 0755、apk 0644；lib 目录 0755、.so 必须 0644
set_perm_recursive $MODPATH/system/priv-app/OpenEUICC 0 0 0755 0644
set_perm_recursive $MODPATH/system/priv-app/OpenEUICC/lib 0 0 0755 0644

# 权限白名单：eUICC 特性声明与 privapp 白名单需可读
set_perm_recursive $MODPATH/system/etc/permissions 0 0 0755 0644
