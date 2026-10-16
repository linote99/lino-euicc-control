package kano.euiccctl;

import java.util.ArrayList;
import java.util.List;

/** 极简 BER-TLV 解析器（够用于 ES10c 响应） */
final class Tlv {
    final int tag;          // 完整 tag（含多字节）
    final byte[] value;     // tag 后的内容
    int offset;             // value 在原缓冲中的起始（便于二次解析）

    Tlv(int tag, byte[] value) { this.tag = tag; this.value = value; }

    /** 解析一段缓冲中的全部 TLV */
    static List<Tlv> parse(byte[] b) { return parse(b, 0, b.length); }

    static List<Tlv> parse(byte[] b, int from, int to) {
        List<Tlv> out = new ArrayList<Tlv>();
        int i = from;
        while (i < to) {
            int t = b[i++] & 0xFF;
            int tag = t;
            // BER-TLV 多字节 tag: 当低 5 位为 0x1F 时表示 tag 继续。
            // 后续每个字节的高位(0x80)为 1 表示"还有下一字节"，为 0 表示 tag 结束。
            //   例: 9F 70  -> t=0x9F 低5位=0x1F -> 续读 0x70, 其 0x80 位=0 -> 结束, tag=0x9F70
            //   例: E3     -> 低5位=0x03 != 0x1F -> 单字节 tag, 不续读
            // 关键: 首个续读字节无论高位如何都要读入(上面已读)，之后再看高位决定是否继续。
            if ((t & 0x1F) == 0x1F) {
                int n = b[i++] & 0xFF;      // 第二个 tag 字节，必须读
                tag = (tag << 8) | n;
                while ((n & 0x80) != 0 && i < to) {   // 仅当该字节高位为 1 才继续
                    n = b[i++] & 0xFF;
                    tag = (tag << 8) | n;
                }
            }
            if (i >= to) break;
            int lb = b[i++] & 0xFF;
            int len;
            if (lb < 0x80) {
                // 短格式：长度就是 lb
                len = lb;
            } else if (lb == 0x80) {
                // 0x80 = 不定长，读到 EOC(00 00) 为止
                int j = i;
                while (j + 1 < to && !(b[j] == 0 && b[j + 1] == 0)) j++;
                len = j - i;
            } else if (lb == 0xFF) {
                // 0xFF 保留，视为异常输入，按剩余处理
                len = to - i;
            } else {
                // 长格式：lb 低 7 位 = 后续长度字节的个数
                int n = lb & 0x7F;
                len = 0;
                for (int k = 0; k < n && i < to; k++) len = (len << 8) | (b[i++] & 0xFF);
            }
            if (i + len > to) len = to - i;
            byte[] v = new byte[len];
            System.arraycopy(b, i, v, 0, len);
            Tlv tlv = new Tlv(tag, v);
            tlv.offset = i;
            out.add(tlv);
            i += len;
        }
        return out;
    }

    /** 深度优先查找第一个指定 tag */
    static Tlv find(List<Tlv> list, int want) {
        for (Tlv t : list) {
            if (t.tag == want) return t;
            List<Tlv> kids = parse(t.value);
            Tlv r = find(kids, want);
            if (r != null) return r;
        }
        return null;
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** BCD 转数字串（低半字节在前，F 为填充） */
    static String bcd(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int lo = x & 0x0F, hi = (x >> 4) & 0x0F;
            sb.append((char) ('0' + lo));
            if (hi != 0x0F) sb.append((char) ('0' + hi));
        }
        return sb.toString();
    }

    /** 数字串转 BCD（奇数位以 F 填充） */
    static byte[] toBcd(String s) {
        int n = (s.length() + 1) / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int lo = s.charAt(2 * i) - '0';
            int hi = (2 * i + 1 < s.length()) ? (s.charAt(2 * i + 1) - '0') : 0x0F;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** hex 字符串转字节（与 hex() 互逆） */
    static byte[] unhex(String h) {
        if (h == null) return new byte[0];
        h = h.replaceAll("[^0-9a-fA-F]", "");
        if (h.length() % 2 != 0) h = "0" + h;
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static String ascii(byte[] b) {
        if (b == null) return "";
        try { return new String(b, "UTF-8"); } catch (Throwable e) { return ""; }
    }
}
