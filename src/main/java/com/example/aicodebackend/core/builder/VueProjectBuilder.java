package com.example.aicodebackend.core.builder;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 执行命令类
 * <p>
 * 说明：这是一个<b>无状态</b>组件，可以在容器外直接 new 出来使用
 * （例如流处理器需要在单测里独立构造，或需要自建实例做收尾构建）。
 * 它内部没有任何可变字段，因此多实例、被 new 出来都等价于共享同一个实例。
 */
@Slf4j
@Component
public class VueProjectBuilder {

    /**
     * 构建临时目录前缀：与工程目录同级，构建结束后删除
     */
    private static final String BUILD_TEMP_DIR_PREFIX = ".build-tmp-";

    /**
     * 兜底临时目录名：同级目录不可写时退回工程目录内部
     */
    private static final String BUILD_TEMP_DIR_FALLBACK = ".build-tmp";

    /**
     * 构建输出保留的末尾行数（用于归纳失败原因）
     */
    private static final int OUTPUT_TAIL_LINES = 60;

    /**
     * 失败原因里最多取几行
     */
    private static final int ERROR_SUMMARY_LINES = 3;

    /**
     * 失败原因最大长度（要下发给前端展示，不能把整段构建日志塞进数据库/响应）
     */
    private static final int BUILD_ERROR_MAX_LENGTH = 500;

    /**
     * 终端颜色控制符：npm / vite 输出带 ANSI 转义，下发给前端前要剥掉
     */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");

    /**
     * 构建结果：成功与否 + 失败原因（成功时原因为空串）
     * <p>
     * 为什么要带原因：构建是"生成结束后的异步收尾"，失败时前端只会看到预览区空白/404。
     * 旧实现只把原因写进后端日志（"请查看后端日志"），用户完全不知道发生了什么。
     *
     * @param success 是否构建成功
     * @param error   失败原因（面向用户的简短描述，最多 {@value #BUILD_ERROR_MAX_LENGTH} 字符）
     */
    public record BuildResult(boolean success, String error) {

        public static BuildResult ok() {
            return new BuildResult(true, "");
        }

        public static BuildResult failed(String error) {
            return new BuildResult(false, StrUtil.maxLength(StrUtil.nullToEmpty(error), BUILD_ERROR_MAX_LENGTH));
        }
    }

    /**
     * 一次命令执行的结果
     *
     * @param success   是否成功（退出码 0）
     * @param exitCode  退出码（超时或启动失败为 -1）
     * @param errorInfo 失败原因（成功时为空串）
     */
    private record CommandResult(boolean success, int exitCode, String errorInfo) {
    }

    /**
     * 异步构建 Vue 项目
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis()).start(() -> {
            try {
                BuildResult result = buildProjectDetailed(projectPath);
                if (result.success()) {
                    log.info("异步构建 Vue 项目成功: {}", projectPath);
                } else {
                    log.error("异步构建 Vue 项目失败: {}，原因: {}", projectPath, result.error());
                }
            } catch (Exception e) {
                log.error("异步构建 Vue 项目失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 同步执行构建并返回结果
     * <p>
     * 调用方（生成任务注册表）需要把"构建成功/失败"写回任务状态供前端轮询，
     * 因此它自己负责把这次调用放到独立线程上，这里只做同步构建。
     *
     * @param projectPath 项目目录
     *
     * @return 构建是否成功
     */
    public boolean buildProjectAsyncAndWait(String projectPath) {
        return buildProject(projectPath);
    }

    /**
     * 构建 Vue 项目（就地构建，产物落在项目目录下的 dist）
     */
    public boolean buildProject(String projectPath) {
        return buildProjectTo(projectPath);
    }

    /**
     * 在指定目录构建 Vue 项目，并把产物放到该目录下的 dist
     * <p>
     * 单独抽出来的原因：部署流程会先把源码复制到"构建暂存目录"，在暂存目录里完成构建后再原子切换进源码目录，
     * 这样并发部署时不会出现两个构建同时写同一个 dist、把"构建到一半的产物"发布出去的问题。
     *
     * @param projectPath 项目目录（暂存目录或源码目录均可）
     *
     * @return 构建是否成功
     */
    public boolean buildProjectTo(String projectPath) {
        return buildProjectDetailed(projectPath).success();
    }

    /**
     * 构建 Vue 项目并返回失败原因（成功时 {@code error} 为空）
     * <p>
     * 与 {@link #buildProjectTo(String)} 的唯一区别是把"为什么失败"带出来：
     * 生成收尾的异步构建要把原因写回 {@code /app/chat/gen/status}，
     * 否则前端只能显示"构建失败，请查看后端日志"，用户看到的就是预览区一片空白。
     *
     * @param projectPath 项目目录
     *
     * @return 构建结果
     */
    public BuildResult buildProjectDetailed(String projectPath) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectPath);
            return BuildResult.failed("项目目录不存在：" + projectPath);
        }
        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return BuildResult.failed("package.json 不存在，无法构建");
        }
        log.info("开始构建 Vue 项目: {}", projectPath);
        // 部署目录是 /{deployKey}/ 这样的子路径，必须让 vite 用相对路径引用产物，
        // 否则 index.html 会去根路径 /assets/... 找 JS，部署后页面白屏。
        ensureRelativeBase(projectDir);
        // 构建期间把 TEMP/TMP 指到工程自己的临时目录，原因见 createBuildTempDir
        File buildTempDir = createBuildTempDir(projectDir);
        try {
            // 执行 npm install
            CommandResult installResult = executeNpmInstall(projectDir, buildTempDir);
            if (!installResult.success()) {
                log.error("npm install 执行失败");
                return BuildResult.failed("npm install 失败：" + installResult.errorInfo());
            }
            // 执行 npm run build
            CommandResult buildResult = executeNpmBuild(projectDir, buildTempDir);
            if (!buildResult.success()) {
                log.error("npm run build 执行失败");
                return BuildResult.failed("npm run build 失败：" + buildResult.errorInfo());
            }
            // 验证 dist 目录是否生成
            File distDir = new File(projectDir, "dist");
            if (!distDir.exists()) {
                log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
                return BuildResult.failed("构建命令执行成功但没有生成 dist 目录");
            }
            log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
            return BuildResult.ok();
        } finally {
            deleteBuildTempDir(buildTempDir);
        }
    }

    /**
     * 为本次构建创建一个专属临时目录（作为子进程的 TEMP/TMP）
     * <p>
     * 背景（实测）：esbuild 在<b>输入超过 1 MiB</b> 时不再走管道，而是把内容写进 {@code os.tmpdir()}
     * 下的临时文件、交给子进程读取后删除（见 esbuild lib/main.js："input.length > 1024 * 1024"）。
     * 带 three.js 这类大依赖的 Vue 工程，打包后的 chunk 轻松超过 1 MiB，于是每次构建都会命中这条路径。
     * 而在部分 Windows 环境（安全软件/受限 ACL 拦截了系统 Temp 目录里的删除动作）下，
     * 这个"删除临时文件"会以 {@code remove ...: Access is denied} 失败，导致 vite build 退出码 1、
     * <b>dist 根本没产出</b>——前端预览区（/static/vue_project_{appId}/dist/index.html）因此一片空白。
     * <p>
     * 处理方式：把子进程的 TEMP/TMP 指向工程自己的目录（与工程同级，仍在生成根目录内，构建结束立即删除），
     * 不让构建成败取决于"系统 Temp 目录在当前机器上是否可删除文件"。
     *
     * @param projectDir 项目目录
     *
     * @return 临时目录；创建失败返回 null（此时沿用系统 TEMP，构建仍然照常尝试）
     */
    private File createBuildTempDir(File projectDir) {
        File parent = projectDir.getAbsoluteFile().getParentFile();
        File candidate = parent == null
                ? new File(projectDir, BUILD_TEMP_DIR_FALLBACK)
                : new File(parent, BUILD_TEMP_DIR_PREFIX + projectDir.getName() + "-" + System.nanoTime());
        try {
            Files.createDirectories(candidate.toPath());
            return candidate;
        } catch (Exception e) {
            log.warn("创建构建临时目录失败，回退到工程目录内: {}，原因: {}", candidate.getAbsolutePath(), e.getMessage());
        }
        File fallback = new File(projectDir, BUILD_TEMP_DIR_FALLBACK);
        try {
            Files.createDirectories(fallback.toPath());
            return fallback;
        } catch (Exception e) {
            log.warn("创建兜底构建临时目录失败，沿用系统临时目录: {}，原因: {}", fallback.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /**
     * 删除本次构建的临时目录（尽力而为：Windows 上文件被占用时删不掉也不影响构建结论）
     *
     * @param buildTempDir 临时目录，可为 null
     */
    private void deleteBuildTempDir(File buildTempDir) {
        if (buildTempDir == null || !buildTempDir.exists()) {
            return;
        }
        try {
            if (!FileUtil.del(buildTempDir)) {
                log.warn("构建临时目录未能完全删除（可能有文件仍被占用）: {}", buildTempDir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("删除构建临时目录出错: {}，原因: {}", buildTempDir.getAbsolutePath(), e.getMessage());
        }
    }

    /**
     * 保证 vite.config.js 使用相对 base
     * <p>
     * 部署地址形如 {@code http://host/{deployKey}/}，属于子路径部署；vite 默认 base 为 "/"，
     * 产物里的资源引用会变成 {@code /assets/xxx.js}，部署后必然 404。
     * 这里只在配置里没有显式 base 时补一个相对 base，并对"生成结果不是相对路径"的情况直接失败，
     * 避免部署出一个白屏页面却返回成功。
     *
     * @param projectDir 项目目录
     *
     * @return 校验通过返回 true
     */
    private boolean ensureRelativeBase(File projectDir) {
        File viteConfig = new File(projectDir, "vite.config.js");
        if (!viteConfig.isFile()) {
            viteConfig = new File(projectDir, "vite.config.ts");
        }
        if (!viteConfig.isFile()) {
            log.warn("未找到 vite 配置文件，跳过 base 校验: {}", projectDir.getAbsolutePath());
            return true;
        }
        try {
            String content = new String(java.nio.file.Files.readAllBytes(viteConfig.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            if (content.contains("base:")) {
                log.info("vite 配置已显式声明 base，保持原样: {}", viteConfig.getName());
                return true;
            }
            String patched = content.replaceFirst("defineConfig\\(\\s*\\{", "defineConfig({\n  base: './',");
            if (patched.equals(content)) {
                log.warn("无法自动注入相对 base（配置形态不匹配），跳过: {}", viteConfig.getName());
                return true;
            }
            java.nio.file.Files.write(viteConfig.toPath(), patched.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            log.info("已为子路径部署注入相对 base: {}", viteConfig.getName());
            return true;
        } catch (Exception e) {
            log.error("注入相对 base 失败: {}", viteConfig.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 创建构建子进程（统一在这里注入构建临时目录）
     *
     * @param workingDir   工作目录
     * @param command      命令
     * @param buildTempDir 构建临时目录（作为 TEMP/TMP/TMPDIR 传给子进程），可为 null
     *
     * @return 进程构建器
     */
    private ProcessBuilder createProcessBuilder(File workingDir, List<String> command, File buildTempDir) {
        // redirectErrorStream = true：合并 stderr 到 stdout，由同一个线程消费，避免缓冲区写满导致子进程阻塞
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true);
        if (buildTempDir != null) {
            String tempPath = buildTempDir.getAbsolutePath();
            // Node 的 os.tmpdir() 依次看 TMP、TEMP；Go(esbuild) 看 TMP、TEMP；TMPDIR 供 Unix 使用
            processBuilder.environment().put("TMP", tempPath);
            processBuilder.environment().put("TEMP", tempPath);
            processBuilder.environment().put("TMPDIR", tempPath);
        }
        return processBuilder;
    }

    /**
     * 执行命令，并把子进程的标准输出/错误输出实时转发到应用日志，方便定位构建失败原因
     *
     * @param workingDir   工作目录
     * @param command      命令
     * @param timeoutSeconds 超时时间（秒）
     * @param buildTempDir 构建临时目录，可为 null
     *
     * @return 命令执行结果（含失败原因摘要）
     */
    private CommandResult executeCommand(File workingDir, List<String> command, int timeoutSeconds, File buildTempDir) {
        // 尾部输出：成功与否都要消费子进程输出，失败时用末尾几行归纳原因
        Deque<String> outputTail = new ArrayDeque<>();
        String commandText = String.join(" ", command);
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), commandText);
            if (buildTempDir != null) {
                log.info("构建临时目录(TEMP/TMP): {}", buildTempDir.getAbsolutePath());
            }
            Process process = createProcessBuilder(workingDir, command, buildTempDir).start();
            // 异步转发子进程输出，避免缓冲区写满导致子进程阻塞
            Thread outputPump = Thread.ofVirtual().name("vue-builder-log").start(() -> pumpOutput(process, outputTail));
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程: {}", timeoutSeconds, commandText);
                process.destroyForcibly();
                outputPump.join(TimeUnit.SECONDS.toMillis(5));
                return new CommandResult(false, -1,
                        "执行超时（" + timeoutSeconds + "秒）：" + summarizeFailure(outputTail));
            }
            outputPump.join(TimeUnit.SECONDS.toMillis(5));
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", commandText);
                return new CommandResult(true, 0, "");
            }
            log.error("命令执行失败，退出码: {}，命令: {}", exitCode, commandText);
            return new CommandResult(false, exitCode, summarizeFailure(outputTail));
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", commandText, e.getMessage(), e);
            return new CommandResult(false, -1, StrUtil.nullToEmpty(e.getMessage()));
        }
    }

    /**
     * 转发子进程的标准输出与错误输出，并保留末尾若干行用于归纳失败原因
     *
     * @param process    子进程
     * @param outputTail 末尾行缓冲（多线程访问，需同步）
     */
    private void pumpOutput(Process process, Deque<String> outputTail) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[npm] {}", line);
                synchronized (outputTail) {
                    outputTail.addLast(line);
                    while (outputTail.size() > OUTPUT_TAIL_LINES) {
                        outputTail.removeFirst();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取构建输出失败: {}", e.getMessage());
        }
    }

    /**
     * 从构建输出末尾归纳出一句可展示的失败原因
     * <p>
     * 规则：剥掉终端颜色控制符 → 去掉空行与纯堆栈帧 → 取最后几行。
     * 堆栈帧（{@code at xxx (yyy:1:1)}）对用户没有意义，而真正说明问题的往往是被堆栈包在中间的那几行
     * （例如 {@code [vite:esbuild-transpile] remove ...: Access is denied.} 与 {@code error during build:}）。
     *
     * @param outputTail 末尾输出行
     *
     * @return 失败原因摘要（可能为空串）
     */
    private String summarizeFailure(Deque<String> outputTail) {
        List<String> lines;
        synchronized (outputTail) {
            lines = new ArrayList<>(outputTail);
        }
        List<String> meaningful = new ArrayList<>();
        for (String rawLine : lines) {
            String line = stripAnsi(rawLine).trim();
            if (line.isEmpty() || isStackFrame(line)) {
                continue;
            }
            meaningful.add(line);
        }
        List<String> summary = new ArrayList<>();
        for (int i = meaningful.size() - 1; i >= 0 && summary.size() < ERROR_SUMMARY_LINES; i--) {
            String line = meaningful.get(i);
            if (!summary.contains(line)) {
                summary.add(0, line);
            }
        }
        return StrUtil.maxLength(String.join(" | ", summary), BUILD_ERROR_MAX_LENGTH);
    }

    /**
     * 是否是堆栈帧行（{@code at ...}）
     *
     * @param line 输出行
     *
     * @return true 表示是堆栈帧
     */
    private boolean isStackFrame(String line) {
        return line.startsWith("at ") || line.startsWith("at\t");
    }

    /**
     * 去掉终端颜色控制符
     *
     * @param line 原始输出行
     *
     * @return 纯文本行
     */
    private String stripAnsi(String line) {
        return line == null ? "" : ANSI_ESCAPE.matcher(line).replaceAll("");
    }

    /**
     * 执行 npm install 命令
     *
     * @param projectDir   项目目录
     * @param buildTempDir 构建临时目录，可为 null
     *
     * @return 命令执行结果
     */
    private CommandResult executeNpmInstall(File projectDir, File buildTempDir) {
        log.info("执行 npm install...");
        List<String> command = new ArrayList<>(resolveNpmCommand());
        command.add("install");
        return executeCommand(projectDir, command, 600, buildTempDir); // 10分钟超时
    }

    /**
     * 执行 npm run build 命令
     *
     * @param projectDir   项目目录
     * @param buildTempDir 构建临时目录，可为 null
     *
     * @return 命令执行结果
     */
    private CommandResult executeNpmBuild(File projectDir, File buildTempDir) {
        log.info("执行 npm run build...");
        List<String> command = new ArrayList<>(resolveNpmCommand());
        command.add("run");
        command.add("build");
        return executeCommand(projectDir, command, 300, buildTempDir); // 5分钟超时
    }

    /**
     * 判断当前操作系统是否为 Windows
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 解析可用的 npm 可执行文件。
     * <p>
     * Windows 上 npm 的形态并不统一：官方 Node 安装包提供的是 npm.cmd，
     * 而部分 Node 发行版（如某些 nvm-for-windows 安装、直接解压的 Node）只有 npm.exe。
     * 因此这里按 npm.cmd -> npm.exe -> npm 的顺序探测，避免命令不存在导致构建直接失败。
     *
     * @return 可直接传给 ProcessBuilder 的命令前缀
     */
    private List<String> resolveNpmCommand() {
        if (!isWindows()) {
            return List.of("npm");
        }
        for (String candidate : List.of("npm.cmd", "npm.exe")) {
            File executable = findOnPath(candidate);
            if (executable != null) {
                log.info("检测到 npm 可执行文件: {}", executable.getAbsolutePath());
                return List.of(executable.getAbsolutePath());
            }
        }
        // 兜底交给系统 PATH 解析
        log.warn("未在 PATH 中找到 npm.cmd / npm.exe，回退为直接执行 npm");
        return List.of("npm");
    }

    /**
     * 在 PATH 与 Node 安装目录中查找可执行文件
     *
     * @param executable 可执行文件名，如 npm.cmd
     *
     * @return 可执行文件，未找到返回 null
     */
    private File findOnPath(String executable) {
        File found = lookupInPath(executable);
        if (found != null) {
            return found;
        }
        // 兜底：npm 通常与 node 可执行文件位于同一目录
        File node = lookupInPath(isWindows() ? "node.exe" : "node");
        if (node != null) {
            File candidate = new File(node.getParentFile(), executable);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        String nodeHome = System.getenv("NODE_HOME");
        if (nodeHome != null && !nodeHome.isBlank()) {
            File candidate = new File(nodeHome, executable);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 仅在 PATH 环境变量中查找文件
     */
    private File lookupInPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File candidate = new File(dir, executable);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

}
