package com.example.aicodebackend.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
     * 异步构建 Vue 项目
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis()).start(() -> {
            try {
                boolean result = buildProject(projectPath);
                if (result) {
                    log.info("异步构建 Vue 项目成功");
                } else {
                    log.error("异步构建 Vue 项目失败");
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
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在: {}", projectPath);
            return false;
        }
        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在: {}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目: {}", projectPath);
        // 部署目录是 /{deployKey}/ 这样的子路径，必须让 vite 用相对路径引用产物，
        // 否则 index.html 会去根路径 /assets/... 找 JS，部署后页面白屏。
        ensureRelativeBase(projectDir);
        // 执行 npm install
        if (!executeNpmInstall(projectDir)) {
            log.error("npm install 执行失败");
            return false;
        }
        // 执行 npm run build
        if (!executeNpmBuild(projectDir)) {
            log.error("npm run build 执行失败");
            return false;
        }
        // 验证 dist 目录是否生成
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists()) {
            log.error("构建完成但 dist 目录未生成: {}", distDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        return true;
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
     * 执行命令，并把子进程的标准输出/错误输出实时转发到应用日志，方便定位构建失败原因
     */
    private boolean executeCommand(File workingDir, List<String> command, int timeoutSeconds) {
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), String.join(" ", command));
            // redirectErrorStream = true：合并 stderr 到 stdout，由同一个线程消费，避免缓冲区写满导致子进程阻塞
            Process process = new ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start();
            // 异步转发子进程输出，避免缓冲区写满导致子进程阻塞
            Thread outputPump = Thread.ofVirtual().name("vue-builder-log").start(() -> pumpOutput(process));
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程: {}", timeoutSeconds, String.join(" ", command));
                process.destroyForcibly();
                return false;
            }
            outputPump.join(TimeUnit.SECONDS.toMillis(5));
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", String.join(" ", command));
                return true;
            }
            log.error("命令执行失败，退出码: {}，命令: {}", exitCode, String.join(" ", command));
            return false;
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", String.join(" ", command), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 转发子进程的标准输出与错误输出
     */
    private void pumpOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[npm] {}", line);
            }
        } catch (Exception e) {
            log.debug("读取构建输出失败: {}", e.getMessage());
        }
    }

    /**
     * 执行 npm install 命令
     */
    private boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        List<String> command = new ArrayList<>(resolveNpmCommand());
        command.add("install");
        return executeCommand(projectDir, command, 600); // 10分钟超时
    }

    /**
     * 执行 npm run build 命令
     */
    private boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        List<String> command = new ArrayList<>(resolveNpmCommand());
        command.add("run");
        command.add("build");
        return executeCommand(projectDir, command, 300); // 5分钟超时
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
