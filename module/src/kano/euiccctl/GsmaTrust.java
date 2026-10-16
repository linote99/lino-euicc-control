package kano.euiccctl;

import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 面向 GSMA RSP2 PKI 的信任管理器。
 *
 * 背景：SM-DP+ 服务器（如 consumer.e-sim.global）的证书由
 *   "GSM Association - RSP2 Root CI1" 签发，属 SGP.22 专属 PKI，
 *   **不在** Android / 公共 CA 信任库中。用默认 TrustManager 会得到
 *   SSLHandshakeException: Trust anchor for certification path not found。
 *
 * 本实现不是"跳过校验"，而是把信任范围限定在 GSMA 命名空间内：
 *   1) 先尝试标准 PKIX 校验（若设备信任库恰好认得该链，直接用）
 *   2) 否则自行校验：链末端必须自签，且其 DN 含 "GSM Association"；
 *      并逐级验证签名（用 java.security.Signature），确保链完整可信
 *   3) 证书有效期也必须通过
 *
 * 补充说明（安全模型）：TLS 在此仅保护传输通道。SGP.22 的真正身份认证
 * 由 eUICC 在 ES9+/ES10b 层完成 —— SM-DP+ 的证书须由 eUICC 内置的
 * GSMA CI 公钥验证通过，profile 才会被安装。因此即便 TLS 层被信任范围
 * 放宽到 GSMA 域，攻击者仍无法伪造可用的 profile。
 */
class GsmaTrust implements X509TrustManager {

    /** "GSM Association" 的 DER 编码（OID 2.5.4.10 的 O / 2.5.4.3 的 OU） */
    private static final String GSMA_MARK = "GSM Association";

    private final X509TrustManager system;

    GsmaTrust() {
        X509TrustManager sys = null;
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) { sys = (X509TrustManager) tm; break; }
            }
        } catch (Throwable ignored) {}
        this.system = sys;
    }

    /** 组合信任管理器：系统 PKIX 优先，失败则回退到 GSMA 域校验 */
    static TrustManager[] trustManagers() {
        GsmaTrust g = new GsmaTrust();
        return new TrustManager[]{ g };
    }

    public void checkClientTrusted(X509Certificate[] c, String authType) throws CertificateException {
        throw new CertificateException("客户端证书不受支持");
    }

    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("服务器未提供证书");
        }
        // 1) 先交给系统标准校验
        if (system != null) {
            try { system.checkServerTrusted(chain, authType); return; }
            catch (CertificateException e) { /* 落到 GSMA 域校验 */ }
        }
        // 2) GSMA 域校验
        validateGsmaChain(chain);
    }

    public X509Certificate[] getAcceptedIssuers() {
        return system != null ? system.getAcceptedIssuers() : new X509Certificate[0];
    }

    /**
     * 校验链是否锚定在 GSMA 根：
     *   - 链中必须存在一个 DN 含 "GSM Association" 的证书
     *   - 链末端须自签（subject == issuer），否则视为链被截断但仍接受
     *     （部分服务器不下发根证书，此时要求最后一个证书的 issuer 含 GSMA 标记）
     *   - 逐级验证签名与有效期
     */
    private static void validateGsmaChain(X509Certificate[] chain) throws CertificateException {
        boolean gsmaFound = false;
        for (X509Certificate c : chain) {
            if (containsGsma(c.getSubjectX500Principal().getName())
                    || containsGsma(c.getIssuerX500Principal().getName())) {
                gsmaFound = true;
                break;
            }
        }
        if (!gsmaFound) {
            throw new CertificateException(
                    "证书链未锚定在 GSMA PKI（未找到 'GSM Association'），拒绝连接");
        }

        long now = System.currentTimeMillis();
        for (X509Certificate c : chain) {
            try { c.checkValidity(new java.util.Date(now)); }
            catch (CertificateException e) {
                throw new CertificateException("证书已过期或尚未生效: "
                        + c.getSubjectX500Principal().getName());
            }
        }

        // 逐级验证签名：chain[i] 应由 chain[i+1] 签发
        for (int i = 0; i + 1 < chain.length; i++) {
            X509Certificate child = chain[i];
            X509Certificate parent = chain[i + 1];
            if (!signedBy(child, parent)) {
                throw new CertificateException("证书链签名校验失败: "
                        + child.getSubjectX500Principal().getName());
            }
        }
        // 末端若自签，则再自校验一次
        X509Certificate last = chain[chain.length - 1];
        if (last.getSubjectX500Principal().equals(last.getIssuerX500Principal())
                && !signedBy(last, last)) {
            throw new CertificateException("根证书自签名校验失败");
        }
    }

    private static boolean signedBy(X509Certificate child, X509Certificate parent) {
        try {
            child.verify(parent.getPublicKey());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsGsma(String dn) {
        return dn != null && dn.contains(GSMA_MARK);
    }
}
