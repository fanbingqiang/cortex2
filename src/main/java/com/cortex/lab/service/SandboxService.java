package com.cortex.lab.service;

import com.cortex.lab.dto.ExecuteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.cortex.lab.dto.ProjectFileDTO;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SandboxService {
    // 代码沙箱：编译执行 Java 代码和 Maven 项目

    // ─── 安全限制 ──────────────────────────────────────
    private static final int MAX_LINES = 300;               // 代码行数上限
    private static final int MAX_BYTES = 100 * 1024;        // 代码大小上限 100KB
    private static final String SANDBOX_IMAGE = "eclipse-temurin:17-jdk";
    private static final int COMPILE_TIMEOUT_S = 15;        // 编译超时
    private static final int RUN_TIMEOUT_S = 10;            // 运行超时
    private static final int DOCKER_TIMEOUT_S = 30;         // Docker 总超时（编译+运行）
    // 禁止的危险 API（正则片段，命中即拒绝执行）
    private static final String[] FORBIDDEN_PATTERNS = {
            "System\\.exit",
            "Runtime\\.getRuntime",
            "Runtime\\.exec",
            "ProcessBuilder",
            "\\bexec\\s*\\(",
            "\\bSocket\\b",
            "ServerSocket",
            "DatagramSocket",
            "URLConnection",
            "HttpURLConnection",
            "Files\\.delete",
            "\\bdeleteIfExists",
            "\\.delete\\(\\)",
            "deleteRecursively",
            "shutdownHook",
            "SecurityManager",
            "Unsafe",
    };

    private final Path workDir;
    private final boolean dockerAvailable;

    public SandboxService() {
        try {
            this.workDir = Files.createTempDirectory("cortex-sandbox-");
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create sandbox directory", e);
        }
        this.dockerAvailable = checkDockerAvailable();
        log.info("Sandbox 初始化完成. Docker 容器隔离: {}", dockerAvailable ? "启用" : "不可用(回退本机执行)");
    }

    private boolean checkDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            return exited && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // 编译并执行单文件 Java 代码（含安全限制：行数/大小/危险 API，优先 Docker 隔离）
    public ExecuteResponse execute(String code) {
        // 1. 行数 / 大小限制
        if (code == null || code.isBlank()) {
            return reject("代码不能为空");
        }
        if (code.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return reject("代码超过大小限制 (" + (MAX_BYTES / 1024) + "KB)");
        }
        if (code.split("\n", -1).length > MAX_LINES) {
            return reject("代码超过行数限制 (" + MAX_LINES + " 行)");
        }

        // 2. 危险 API 拦截
        String banned = findBannedApi(code);
        if (banned != null) {
            return reject("检测到禁止使用的 API: " + banned + "（沙箱安全限制，防止恶意代码）");
        }

        // 3. 执行：优先 Docker 容器隔离，否则回退本机
        if (dockerAvailable) {
            return runInDocker(code);
        }
        log.warn("Sandbox 未启用 Docker 隔离，回退本机执行（仅限开发环境，禁止用于生产）");
        return runLocally(code);
    }

    private ExecuteResponse reject(String message) {
        ExecuteResponse response = new ExecuteResponse();
        response.setSuccess(false);
        response.setExitCode(-1);
        response.setStderr(message);
        response.setError(message);
        return response;
    }

    /** 扫描代码中的危险 API，返回第一个命中的名称；无命中返回 null */
    private String findBannedApi(String code) {
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (code.matches("(?s).*" + pattern + ".*")) {
                return pattern.replace("\\b", "").replace("\\s*\\\\(", "");
            }
        }
        return null;
    }

    /** Docker 容器隔离执行：禁网、限内存 256M、限 CPU 0.5 核、限进程数 100 */
    private ExecuteResponse runInDocker(String code) {
        ExecuteResponse response = new ExecuteResponse();
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        Path tempDir = null;

        try {
            tempDir = Files.createDirectory(workDir.resolve(uuid));
            String className = extractClassName(code);
            if (className == null) {
                className = "Main" + uuid;
                code = code.replaceFirst("(public\\s+)?class\\s+\\w+", "public class " + className);
            }

            Path javaFile = tempDir.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

            // 容器内：javac 失败则输出错误并退出；成功则运行
            String containerName = "cortex-sbx-" + uuid;
            String cmd = "if javac -encoding UTF-8 " + className + ".java 2> compile_err.txt; then "
                    + "java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp . " + className
                    + "; else cat compile_err.txt; exit 1; fi";

            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "--rm", "--name", containerName,
                    "--network", "none",
                    "--memory", "256m",
                    "--cpus", "0.5",
                    "--pids-limit", "100",
                    "-v", tempDir.toAbsolutePath() + ":/sandbox",
                    "-w", "/sandbox",
                    SANDBOX_IMAGE,
                    "bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Thread reader = readOutputAsync(process, output);
            boolean finished = process.waitFor(DOCKER_TIMEOUT_S, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                killContainer(containerName);
                response.setSuccess(false);
                response.setExitCode(-1);
                response.setStderr("执行超时 (" + DOCKER_TIMEOUT_S + "s)");
                response.setError("Execution timed out");
                return response;
            }
            reader.join(2000);

            response.setSuccess(process.exitValue() == 0);
            response.setExitCode(process.exitValue());
            response.setStdout(output.toString());
            response.setStderr("");
            response.setError(process.exitValue() != 0 ? "编译或运行错误" : null);

        } catch (Exception e) {
            log.error("Sandbox docker execution error", e);
            response.setSuccess(false);
            response.setExitCode(-1);
            response.setError(e.getMessage());
        } finally {
            deleteTempDir(tempDir);
        }
        return response;
    }

    /** 回退：本机 javac/java 执行（仅开发环境） */
    private ExecuteResponse runLocally(String code) {
        ExecuteResponse response = new ExecuteResponse();
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        Path tempDir = null;

        try {
            tempDir = Files.createDirectory(workDir.resolve(uuid));
            String className = extractClassName(code);
            if (className == null) {
                className = "Main" + uuid;
                code = code.replaceFirst("(public\\s+)?class\\s+\\w+", "public class " + className);
            }

            Path javaFile = tempDir.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

            ProcessBuilder compilePb = new ProcessBuilder("javac", "-encoding", "UTF-8", javaFile.toString());
            compilePb.redirectErrorStream(true);
            Process compileProcess = compilePb.start();

            Charset outputCharset = isWindows() ? Charset.forName("GBK") : StandardCharsets.UTF_8;
            BufferedReader compileReader = new BufferedReader(new InputStreamReader(compileProcess.getInputStream(), outputCharset));
            StringBuilder compileOutput = new StringBuilder();
            String line;
            while ((line = compileReader.readLine()) != null) {
                compileOutput.append(line).append("\n");
            }
            compileProcess.waitFor(COMPILE_TIMEOUT_S, TimeUnit.SECONDS);

            if (compileProcess.exitValue() != 0) {
                response.setSuccess(false);
                response.setExitCode(compileProcess.exitValue());
                response.setStdout("");
                response.setStderr(compileOutput.toString());
                response.setError("Compilation failed");
                return response;
            }

            ProcessBuilder runPb = new ProcessBuilder("java", "-Dfile.encoding=UTF-8", "-cp", tempDir.toString(), className);
            runPb.redirectErrorStream(true);
            Process runProcess = runPb.start();

            if (!runProcess.waitFor(RUN_TIMEOUT_S, TimeUnit.SECONDS)) {
                runProcess.destroyForcibly();
                response.setSuccess(false);
                response.setExitCode(-1);
                response.setStderr("执行超时 (" + RUN_TIMEOUT_S + "s)");
                response.setError("Execution timed out (10s)");
                return response;
            }

            BufferedReader runReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), outputCharset));
            StringBuilder runOutput = new StringBuilder();
            while ((line = runReader.readLine()) != null) {
                runOutput.append(line).append("\n");
            }

            response.setSuccess(runProcess.exitValue() == 0);
            response.setExitCode(runProcess.exitValue());
            response.setStdout(runOutput.toString());
            response.setStderr("");
            response.setError(runProcess.exitValue() != 0 ? "Runtime error" : null);

        } catch (Exception e) {
            log.error("Sandbox execution error", e);
            response.setSuccess(false);
            response.setExitCode(-1);
            response.setError(e.getMessage());
        } finally {
            deleteTempDir(tempDir);
        }
        return response;
    }

    /** 异步读取子进程输出，避免管道缓冲区写满导致死锁 */
    private Thread readOutputAsync(Process process, StringBuilder output) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void killContainer(String containerName) {
        try {
            Process p = new ProcessBuilder("docker", "kill", containerName)
                    .redirectErrorStream(true).start();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private void deleteTempDir(Path tempDir) {
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String extractClassName(String code) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("class\\s+(\\w+)");
        java.util.regex.Matcher m = p.matcher(code);
        if (m.find()) return m.group(1);
        return null;
    }

    private void cleanup() {
        deleteTempDir(workDir);
    }

    // 构建 Maven 项目（编译整个项目目录）
    public BuildResult buildMavenProject(List<ProjectFileDTO> files) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        Path projectDir = workDir.resolve(uuid);
        BuildResult result = new BuildResult();

        try {
            // Write all project files
            for (ProjectFileDTO f : files) {
                Path target = projectDir.resolve(f.getPath().replace('/', File.separatorChar));
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.getContent(), StandardCharsets.UTF_8);
            }

            log.info("Maven project written to: {}", projectDir);

            // Run mvn compile
            ProcessBuilder pb = new ProcessBuilder(
                findMvn(), "compile", "-q",
                "-Dmaven.test.skip=true",
                "-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugins=error"
            );
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            long start = System.currentTimeMillis();
            Process process = pb.start();

            Charset outputCharset = isWindows() ? Charset.forName("GBK") : StandardCharsets.UTF_8;
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), outputCharset));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.setSuccess(false);
                result.setOutput("编译超时（120s）");
                result.setProjectDir(projectDir.toString());
                return result;
            }

            long elapsed = System.currentTimeMillis() - start;
            result.setSuccess(process.exitValue() == 0);
            result.setExitCode(process.exitValue());
            result.setOutput(output.toString());
            result.setElapsedMs(elapsed);
            result.setProjectDir(projectDir.toString());
            return result;

        } catch (Exception e) {
            log.error("Maven build error", e);
            result.setSuccess(false);
            result.setOutput("构建失败: " + e.getMessage());
            return result;
        }
    }

    // 查找系统可用的 mvn 命令路径
    private String findMvn() {
        // Try common Maven locations
        String[] candidates = {
            "mvn.cmd", "mvn", "mvn.bat",
            "D:/apache-maven-3.9.11/bin/mvn.cmd",
            "D:/apache-maven-3.9.11/bin/mvn",
            "/d/apache-maven-3.9.11/bin/mvn",
            "C:/Program Files/apache-maven-3.9.11/bin/mvn.cmd"
        };
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version")
                    .redirectErrorStream(true).start();
                boolean exited = p.waitFor(5, TimeUnit.SECONDS);
                if (exited && p.exitValue() == 0) {
                    return c;
                }
            } catch (Exception ignored) {}
        }
        // On Windows, try from PATH with .cmd extension
        if (isWindows()) {
            try {
                Process p = new ProcessBuilder("where", "mvn").start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return "mvn.cmd";
                }
            } catch (Exception ignored) {}
        }
        return "mvn.cmd"; // fallback
    }

    @lombok.Data
    public static class BuildResult {
        // Maven 构建结果
        private boolean success;
        private int exitCode;
        private String output;
        private long elapsedMs;
        private String projectDir;
    }
}
