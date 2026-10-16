#!/system/bin/sh
# 开机自动把本应用加入 deviceidle 白名单。
#
# 原因：Android 8+ 禁止从后台启动服务，实测 `am startservice` 会被拒
#   ("Background start not allowed: ... to kano.euiccctl/.CtlService")
# 加入 deviceidle 白名单后放行。该白名单不持久，重启会丢，故每次开机重加。
# 插件侧也会在每次调用前幂等补加一次（自愈），两处互为兜底。

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 5

cmd deviceidle whitelist +kano.euiccctl >/dev/null 2>&1

# 兜底：部分 ROM 的 cmd deviceidle 在开机早期不可用，用 dumpsys 校验一次
if ! dumpsys deviceidle whitelist 2>/dev/null | grep -q "kano.euiccctl"; then
    sleep 10
    cmd deviceidle whitelist +kano.euiccctl >/dev/null 2>&1
fi
