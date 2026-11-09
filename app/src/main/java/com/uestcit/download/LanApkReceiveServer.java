package com.uestcit.download;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本机局域网 HTTP：手机扫码打开上传页，POST APK 到车机。
 * 退出 Activity 时 {@link #stop()} 即停止接收。
 */
final class LanApkReceiveServer {

    private static final String TAG = "LanApkReceive";
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    /** 单文件上限 512MiB。 */
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final int DEFAULT_PORT = 8765;

    interface Listener {
        void onStatus(String line);

        /** 已落盘且通过魔数校验的 APK（已规范为 .apk 名）。 */
        void onApkReceived(File apkFile);
    }

    private final File saveDir;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String token;
    private ServerSocket serverSocket;
    private ExecutorService acceptPool;
    private volatile int boundPort = -1;

    LanApkReceiveServer(File saveDir, Listener listener) {
        this.saveDir = saveDir;
        this.listener = listener;
        this.token = randomToken(8);
    }

    String token() {
        return token;
    }

    int port() {
        return boundPort;
    }

    /** 上传页完整 URL（需本机局域网 IP）。 */
    String buildUrl(String lanIp) {
        return "http://" + lanIp + ":" + boundPort + "/xfer/?t=" + token;
    }

    synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            throw new IOException("无法创建目录: " + saveDir);
        }
        serverSocket = new ServerSocket(DEFAULT_PORT);
        boundPort = serverSocket.getLocalPort();
        running.set(true);
        acceptPool = Executors.newCachedThreadPool();
        acceptPool.execute(this::acceptLoop);
        notifyStatus("HTTP 监听 :" + boundPort);
    }

    synchronized void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // ignore
            }
            serverSocket = null;
        }
        if (acceptPool != null) {
            acceptPool.shutdownNow();
            acceptPool = null;
        }
        boundPort = -1;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                acceptPool.execute(() -> handleClient(socket));
            } catch (SocketException e) {
                if (running.get()) {
                    Log.w(TAG, "accept: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept io: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             InputStream rawIn = new BufferedInputStream(s.getInputStream());
             OutputStream rawOut = new BufferedOutputStream(s.getOutputStream())) {
            String requestLine = readLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeResponse(rawOut, 400, "text/plain; charset=utf-8", "Bad Request");
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String pathQuery = parts[1];
            Headers headers = readHeaders(rawIn);

            String path = pathQuery;
            String query = "";
            int q = pathQuery.indexOf('?');
            if (q >= 0) {
                path = pathQuery.substring(0, q);
                query = pathQuery.substring(q + 1);
            }
            String reqToken = queryParam(query, "t");
            if (reqToken == null) {
                reqToken = headers.get("x-token");
            }
            if (!token.equals(reqToken)) {
                writeResponse(rawOut, 403, "text/plain; charset=utf-8", "Forbidden");
                return;
            }

            if ("GET".equals(method) && (path.equals("/") || path.equals("/xfer")
                    || path.equals("/xfer/"))) {
                writeResponse(rawOut, 200, "text/html; charset=utf-8", uploadHtml());
                return;
            }
            if ("POST".equals(method) && (path.equals("/xfer") || path.equals("/xfer/")
                    || path.equals("/upload"))) {
                handleUpload(rawIn, headers, rawOut);
                return;
            }
            writeResponse(rawOut, 404, "text/plain; charset=utf-8", "Not Found");
        } catch (Exception e) {
            Log.w(TAG, "client: " + e.getMessage());
            notifyStatus("请求失败: " + e.getMessage());
        }
    }

    private void handleUpload(InputStream in, Headers headers, OutputStream out)
            throws Exception {
        long contentLength = parseLong(headers.get("content-length"), -1);
        if (contentLength > MAX_BYTES) {
            writeResponse(out, 413, "text/plain; charset=utf-8", "文件过大");
            return;
        }
        if (contentLength <= 0) {
            writeResponse(out, 400, "text/plain; charset=utf-8", "缺少 Content-Length");
            return;
        }
        notifyStatus("接收中…");
        String originalName = "upload.apk";
        String cd = headers.get("content-disposition");
        if (cd != null) {
            String fn = filenameFromDisposition(cd);
            if (fn != null && !fn.isEmpty()) {
                originalName = fn;
            }
        }
        String xName = headers.get("x-filename");
        if (xName != null && !xName.isEmpty()) {
            try {
                originalName = URLDecoder.decode(xName, "UTF-8");
            } catch (Exception e) {
                originalName = xName;
            }
        }

        File tmp = File.createTempFile("xfer-", ".bin", saveDir);
        try {
            copyLimited(in, tmp, contentLength);

            if (!ApkFileNames.looksLikeApkName(originalName)
                    && !ApkFileNames.hasZipApkMagic(tmp)) {
                writeResponse(out, 400, "text/plain; charset=utf-8",
                        "文件名不像 APK，且内容非 ZIP");
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            if (!ApkFileNames.hasZipApkMagic(tmp)) {
                writeResponse(out, 400, "text/plain; charset=utf-8",
                        "不是有效 APK（文件头非 PK）");
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }

            String installName = ApkFileNames.normalizeInstallFileName(originalName);
            File dest = new File(saveDir, installName);
            if (dest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            if (!tmp.renameTo(dest)) {
                copyLimited(new BufferedInputStream(
                        new java.io.FileInputStream(tmp)), dest, tmp.length());
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            writeResponse(out, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"name\":\"" + installName + "\"}");
            notifyStatus("已接收: " + dest.getName());
            if (listener != null) {
                listener.onApkReceived(dest);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    private static void copyLimited(InputStream in, File out, long length)
            throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            long left = length < 0 ? MAX_BYTES : Math.min(length, MAX_BYTES);
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                fos.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static String filenameFromDisposition(String cd) {
        int idx = cd.toLowerCase(Locale.US).indexOf("filename=");
        if (idx < 0) {
            return null;
        }
        String v = cd.substring(idx + "filename=".length()).trim();
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            if (end > 1) {
                return v.substring(1, end);
            }
        }
        int semi = v.indexOf(';');
        if (semi > 0) {
            v = v.substring(0, semi);
        }
        return v.replace("\"", "").trim();
    }

    private String uploadHtml() {
        // 用 fetch + octet-stream，避免手机浏览器 multipart 边界解析差异
        return "<!DOCTYPE html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>传 APK 到车机</title>"
                + "<style>body{font-family:sans-serif;padding:16px;background:#1c2229;color:#f2f5f8}"
                + "button{font-size:18px;padding:12px 20px;background:#2bb673;border:0;color:#fff;"
                + "border-radius:8px;width:100%;margin-top:12px}input{margin:12px 0;width:100%}"
                + "p,#msg{color:#9aa5b1;font-size:14px}</style></head><body>"
                + "<h2>上传 APK</h2>"
                + "<p>请连接车机 WiFi 热点后选择文件。微信另存成 xxx.apk.1 也可。</p>"
                + "<input id=f type=file>"
                + "<button id=btn type=button>上传并安装</button>"
                + "<p id=msg></p>"
                + "<script>(function(){var t=" + jsonStr(token) + ";"
                + "document.getElementById('btn').onclick=async function(){"
                + "var f=document.getElementById('f').files[0];var m=document.getElementById('msg');"
                + "if(!f){m.textContent='请先选择文件';return;}"
                + "m.textContent='上传中…';"
                + "try{var r=await fetch('/xfer/?t='+encodeURIComponent(t),{method:'POST',"
                + "headers:{'Content-Type':'application/octet-stream',"
                + "'X-Filename':encodeURIComponent(f.name)},body:f});"
                + "var tx=await r.text();m.textContent=r.ok?'已上传，车机正在安装':'失败 '+r.status+' '+tx;"
                + "}catch(e){m.textContent='网络错误: '+e;}};})();</script>"
                + "</body></html>";
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void notifyStatus(String line) {
        if (listener != null) {
            listener.onStatus(line);
        }
    }

    private static void writeResponse(OutputStream out, int code, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(UTF8);
        String status = code == 200 ? "OK" : (code == 403 ? "Forbidden"
                : (code == 404 ? "Not Found" : "Error"));
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));
        out.write(bytes);
        out.flush();
    }

    private static Headers readHeaders(InputStream in) throws IOException {
        Headers h = new Headers();
        while (true) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) {
                break;
            }
            int c = line.indexOf(':');
            if (c > 0) {
                h.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                        line.substring(c + 1).trim());
            }
        }
        return h;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                break;
            }
            if (prev == '\r') {
                bos.write('\r');
            }
            if (b != '\r') {
                bos.write(b);
            }
            prev = b;
            if (bos.size() > 8192) {
                break;
            }
        }
        if (b < 0 && bos.size() == 0) {
            return null;
        }
        return bos.toString(UTF8.name());
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = pair.substring(0, eq);
            if (key.equals(k)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String randomToken(int len) {
        final char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet[r.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    /**
     * SoftAp 侧 IPv4 候选（含打分），供 UI 日志与选取。
     */
    static final class Ipv4Candidate {
        final String iface;
        final String host;
        /** 越高越像 SoftAp 网关；负分表示应排除（不用于出码）。 */
        final int score;

        Ipv4Candidate(String iface, String host, int score) {
            this.iface = iface;
            this.host = host;
            this.score = score;
        }
    }

    /**
     * 枚举可用局域网 IPv4 并打分；排除 loopback / link-local / 典型非 SoftAp 网卡。
     */
    static List<Ipv4Candidate> listLanIpv4Candidates() {
        List<Ipv4Candidate> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return out;
            }
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                String name = nif.getName();
                if (name == null) {
                    name = "?";
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    if (host == null || host.startsWith("127.") || host.startsWith("169.254.")) {
                        continue;
                    }
                    out.add(new Ipv4Candidate(name, host, scoreSoftApCandidate(name, host)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "listLanIpv4Candidates: " + e.getMessage());
        }
        return out;
    }

    /**
     * 选 SoftAp 网关 IPv4；无线 SoftAp 迹象时才返回，避免把 eth/STA 等错误 IP 编进二维码。
     */
    static String pickLanIpv4() {
        return pickLanIpv4(listLanIpv4Candidates());
    }

    /** 在已枚举候选中取最高分且分数 ≥ 最低 SoftAp 门槛。 */
    static String pickLanIpv4(List<Ipv4Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Ipv4Candidate best = null;
        for (Ipv4Candidate c : candidates) {
            if (c.score < MIN_SOFTAP_SCORE) {
                continue;
            }
            if (best == null || c.score > best.score) {
                best = c;
            }
        }
        return best != null ? best.host : null;
    }

    /** 低于此分不用于出码（等 SoftAp 网卡真正起来）。 */
    private static final int MIN_SOFTAP_SCORE = 50;

    /**
     * SoftAp 可能性打分：网卡名优先，其次常见 SoftAp 网段；排除以太网/蜂窝/USB 网卡。
     */
    private static int scoreSoftApCandidate(String iface, String host) {
        String ifl = iface.toLowerCase(Locale.US);
        // 车机其它网卡：永不选
        if (ifl.startsWith("eth") || ifl.startsWith("rmnet") || ifl.startsWith("ccmni")
                || ifl.startsWith("usb") || ifl.startsWith("rndis") || ifl.startsWith("v4-")
                || ifl.contains("dummy")) {
            return -100;
        }
        int score = 0;
        // SoftAp 常见接口名
        if (ifl.equals("ap0") || ifl.startsWith("softap") || ifl.startsWith("swlan")
                || ifl.equals("wlan1") || ifl.contains("ap0")) {
            score += 100;
        } else if (ifl.startsWith("wlan")) {
            score += 20;
        }
        // 常见 SoftAp DHCP 网段
        if (host.startsWith("192.168.43.") || host.startsWith("192.168.42.")
                || host.startsWith("192.168.137.")) {
            score += 80;
        } else if (host.startsWith("192.168.")) {
            score += 40;
        } else if (host.startsWith("10.") || host.startsWith("172.")) {
            // 可能是 SoftAp 自定义段，但需配合 SoftAp 网卡名才够门槛
            score += 10;
        }
        return score;
    }

    private static final class Headers {
        private final java.util.HashMap<String, String> map = new java.util.HashMap<>();

        void put(String k, String v) {
            map.put(k, v);
        }

        String get(String k) {
            return map.get(k);
        }
    }
}
